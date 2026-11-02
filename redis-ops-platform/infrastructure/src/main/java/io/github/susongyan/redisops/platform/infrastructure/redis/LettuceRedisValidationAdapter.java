package io.github.susongyan.redisops.platform.infrastructure.redis;

import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.validation.*;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisServerCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.codec.ByteArrayCodec;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

@Component
public class LettuceRedisValidationAdapter implements RedisValidationPort {
    private final RedisConnectionProfileProvider profiles;
    /**
     * Long-lived connections shared by every scan page and key inspection. Validation walks millions of keys;
     * creating (and worst case blocking in) a client shutdown per call made each page/key cost seconds, and
     * a per-call teardown waits out reconnect backoff against unreachable nodes. Entries live until the
     * application stops or a dropped connection is retried; cluster connections self-heal topology on their own.
     */
    private final ConcurrentMap<String, Pooled> pool = new ConcurrentHashMap<>();
    /** Pipelined batch connections share one client (and its event loops) for the adapter's lifetime. */
    private volatile RedisClient pipelineClient;
    public LettuceRedisValidationAdapter(RedisConnectionProfileProvider profiles) {
        this.profiles = profiles;
    }

    @PreDestroy
    public void shutdown() {
        pool.values().forEach(Pooled::close);
        pool.clear();
        if (pipelineClient != null)
            pipelineClient.shutdown();
    }

    @Override
    public ScanPage scan(long clusterId, int database, String cursor, int count) {
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            if (profile.mode() == ClusterMode.CLUSTER)
                return scanClusterNode(profile, database, cursor, count);
            return scanEndpoint(profile, profile.seedEndpoints().get(0), database, cursor, count);
        }
    }

    /**
     * Cluster SCAN cursors are node-bound in Lettuce, so the cursor string carries the master node id
     * ({@code nodeId:cursor}) and every page scans the node it names directly. When one master is exhausted
     * the cursor moves to the next master; "0" alone means all done.
     */
    private ScanPage scanClusterNode(RedisConnectionProfile profile, int database, String cursor, int count) {
        List<String[]> masters = clusterMasters(profile, database);
        String nodeId;
        String nodeCursor;
        if ("0".equals(cursor)) {
            nodeId = masters.get(0)[0];
            nodeCursor = "0";
        } else {
            int separator = cursor.indexOf(':');
            if (separator <= 0)
                throw new IllegalArgumentException("invalid cluster scan cursor");
            nodeId = cursor.substring(0, separator);
            nodeCursor = cursor.substring(separator + 1);
        }
        String[] target = masters.stream().filter(master -> master[0].equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("cluster scan node disappeared"));
        ScanPage page = scanEndpoint(profile, target[1], database, nodeCursor, count);
        return new ScanPage(nextClusterCursor(masters, nodeId, page.nextCursor()), page.keys());
    }

    private static String nextClusterCursor(List<String[]> masters, String nodeId, String pageCursor) {
        if (!"0".equals(pageCursor))
            return nodeId + ":" + pageCursor;
        for (int index = 0; index < masters.size(); index++)
            if (masters.get(index)[0].equals(nodeId))
                return index + 1 >= masters.size() ? "0" : masters.get(index + 1)[0] + ":0";
        return "0";
    }

    @Override
    public List<ScanShard> scanShards(long clusterId, int database) {
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            if (profile.mode() != ClusterMode.CLUSTER)
                return List.of(new ScanShard("default", ""));
            return clusterMasters(profile, database).stream().map(master -> new ScanShard(master[0], master[1]))
                    .toList();
        }
    }

    @Override
    public ScanPage scan(long clusterId, int database, String shardId, String cursor, int count) {
        if ("default".equals(shardId))
            return scan(clusterId, database, cursor, count);
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            if (profile.mode() != ClusterMode.CLUSTER)
                throw new IllegalArgumentException("scan shard is only valid for Cluster");
            String endpoint = clusterMasters(profile, database).stream()
                    .filter(master -> shardId.equals(master[0])).map(master -> master[1]).findFirst()
                    .orElseThrow(() -> new IllegalStateException("cluster scan shard disappeared"));
            return scanEndpoint(profile, endpoint, database, cursor, count);
        }
    }
    @Override
    public long countKeys(long clusterId, int database) {
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            // Cluster totals must sum every master from CLUSTER NODES — seed endpoints may cover only a subset.
            if (profile.mode() == ClusterMode.CLUSTER) {
                long total = 0;
                for (String[] master : clusterMasters(profile, database))
                    total += withPooledBytes(bytesKey(master[1], database), () -> bytes(profile, master[1], database),
                            commands -> commands.server().dbsize());
                return total;
            }
            long total = 0;
            for (String endpoint : profile.seedEndpoints()) {
                RedisClient client = RedisClient.create(uri(endpoint, database, profile));
                try (StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE)) {
                    total += connection.sync().dbsize();
                } finally {
                    client.shutdown();
                }
            }
            return total;
        }
    }

    @Override
    public Optional<ValidationValue> inspect(long clusterId, int database, byte[] key, ValidationTask task) {
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            return withSyncCommands(profile, database,
                    commands -> inspect(commands.keys(), commands.server(), commands.strings(), key, task));
        }
    }

    /** Per-group cap on the value bytes one pipelined batch may hold, so large keys cannot balloon the heap. */
    private static final long PIPELINE_MEMORY_BUDGET = 32L * 1024 * 1024;
    private static final long SCAN_PAGE = 5000;
    private static final long LIST_PAGE = 1000;

    private synchronized RedisClient pipelineClient() {
        if (pipelineClient == null)
            pipelineClient = RedisClient.create();
        return pipelineClient;
    }

    /**
     * Pipelined per-page counterpart of {@link #inspect}: TYPE/TTL/MEMORY for the whole batch in one flush, then
     * value digests in rounds (strings chunked, containers walked by cursor, streams DUMPed). Semantic types are
     * digested up to {@code largeKeyThresholdBytes} — chunk/cursor walking has no all-or-nothing size cap — while
     * streams keep the {@code maxDeepCompareBytes} DUMP limit. A fresh short-lived connection per endpoint avoids
     * toggling auto-flush on the shared pooled connections.
     */
    @Override
    public List<Optional<ValidationValue>> inspectBatch(long clusterId, int database, List<byte[]> keys,
            ValidationTask task) {
        if (keys.isEmpty())
            return List.of();
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            @SuppressWarnings("unchecked")
            Optional<ValidationValue>[] out = new Optional[keys.size()];
            if (profile.mode() == ClusterMode.CLUSTER)
                for (Map.Entry<String, List<Integer>> group : slotsByEndpoint(profile, database, keys).entrySet())
                    inspectEndpointBatch(profile, group.getKey(), database, keys, group.getValue(), task, out);
            else
                inspectEndpointBatch(profile, profile.seedEndpoints().get(0), database, keys,
                        IntStream.range(0, keys.size()).boxed().toList(), task, out);
            return Arrays.asList(out);
        }
    }

    private void inspectEndpointBatch(RedisConnectionProfile profile, String endpoint, int database,
            List<byte[]> keys, List<Integer> positions, ValidationTask task, Optional<ValidationValue>[] out) {
        try {
            endpointBatch(profile, endpoint, database, keys, positions, task, out);
        } catch (RuntimeException error) {
            if (!transientError(error))
                throw error;
            endpointBatch(profile, endpoint, database, keys, positions, task, out);
        }
    }

    private record MasterSlots(String endpoint, List<long[]> ranges) {
        boolean owns(int slot) {
            return ranges.stream().anyMatch(range -> slot >= range[0] && slot <= range[1]);
        }
    }

    /** Groups key positions by the master owning each key's slot, so a batch pipelines only same-node commands. */
    private Map<String, List<Integer>> slotsByEndpoint(RedisConnectionProfile profile, int database,
            List<byte[]> keys) {
        String nodes = withPooledText("text:" + profile.clusterId() + ":" + database,
                () -> text(profile, profile.seedEndpoints().get(0), database), RedisCommands::clusterNodes);
        List<MasterSlots> masters = new ArrayList<>();
        for (String line : nodes.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 8 || !fields[2].contains("master") || fields[2].contains("fail"))
                continue;
            List<long[]> ranges = new ArrayList<>();
            for (int field = 8; field < fields.length; field++) {
                String token = fields[field];
                if (token.startsWith("["))
                    continue; // migrating/importing markers, not owned slots
                int dash = token.indexOf('-');
                ranges.add(dash > 0
                        ? new long[]{Long.parseLong(token.substring(0, dash)),
                                Long.parseLong(token.substring(dash + 1))}
                        : new long[]{Long.parseLong(token), Long.parseLong(token)});
            }
            masters.add(new MasterSlots(fields[1].split("@", 2)[0], ranges));
        }
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            int slot = SlotHash.getSlot(keys.get(index));
            MasterSlots owner = masters.stream().filter(master -> master.owns(slot)).findFirst()
                    .orElse(masters.get(0));
            groups.computeIfAbsent(owner.endpoint(), unused -> new ArrayList<>()).add(index);
        }
        return groups;
    }

    private void endpointBatch(RedisConnectionProfile profile, String endpoint, int database, List<byte[]> keys,
            List<Integer> positions, ValidationTask task, Optional<ValidationValue>[] out) {
        try (StatefulRedisConnection<byte[], byte[]> connection = pipelineClient()
                .connect(ByteArrayCodec.INSTANCE, uri(endpoint, database, profile))) {
            connection.setTimeout(Duration.ofSeconds(120));
            connection.setAutoFlushCommands(false);
            RedisAsyncCommands<byte[], byte[]> async = connection.async();
            List<RedisFuture<String>> types = new ArrayList<>(positions.size());
            List<RedisFuture<Long>> ttls = new ArrayList<>(positions.size());
            List<RedisFuture<Long>> memories = new ArrayList<>(positions.size());
            for (int position : positions) {
                byte[] key = keys.get(position);
                types.add(async.type(key));
                ttls.add(async.ttl(key));
                memories.add(async.memoryUsage(key));
            }
            connection.flushCommands();
            List<BatchKey> batch = new ArrayList<>(positions.size());
            for (int index = 0; index < positions.size(); index++) {
                int position = positions.get(index);
                String type = (String) await(types.get(index));
                if ("none".equals(type)) {
                    out[position] = Optional.empty();
                    continue;
                }
                long ttl = normalizeTtl((Long) await(ttls.get(index)));
                Long memoryUsage = (Long) await(memories.get(index));
                long size = memoryUsage == null ? 0 : memoryUsage;
                if (size > task.largeKeyThresholdBytes())
                    out[position] = Optional.of(
                            new ValidationValue(type, size, ttl, null, "METADATA", "LARGE_KEY_THRESHOLD"));
                else if (!("string".equals(type) || "hash".equals(type) || "list".equals(type)
                        || "set".equals(type) || "zset".equals(type) || "stream".equals(type)))
                    out[position] = Optional.of(
                            new ValidationValue(type, size, ttl, null, "METADATA", "UNSUPPORTED_TYPE"));
                else
                    batch.add(new BatchKey(position, type, ttl, size));
            }
            digestBatch(async, connection, batch, keys, task, out);
        }
    }

    private void digestBatch(RedisAsyncCommands<byte[], byte[]> async,
            StatefulRedisConnection<byte[], byte[]> connection, List<BatchKey> batch, List<byte[]> keys,
            ValidationTask task, Optional<ValidationValue>[] out) {
        List<BatchKey> group = new ArrayList<>();
        long budget = 0;
        for (BatchKey key : batch) {
            if (!group.isEmpty() && budget + key.size > PIPELINE_MEMORY_BUDGET) {
                digestGroup(async, connection, group, keys, task, out);
                group = new ArrayList<>();
                budget = 0;
            }
            group.add(key);
            budget += key.size;
        }
        if (!group.isEmpty())
            digestGroup(async, connection, group, keys, task, out);
    }

    private void digestGroup(RedisAsyncCommands<byte[], byte[]> async,
            StatefulRedisConnection<byte[], byte[]> connection, List<BatchKey> group, List<byte[]> keys,
            ValidationTask task, Optional<ValidationValue>[] out) {
        Map<BatchKey, RedisFuture<?>> counts = new LinkedHashMap<>();
        for (BatchKey key : group) {
            byte[] name = keys.get(key.position);
            key.sha.update(key.type.getBytes(StandardCharsets.US_ASCII));
            counts.put(key, switch (key.type) {
                case "string" -> async.strlen(name);
                case "hash" -> async.hlen(name);
                case "set" -> async.scard(name);
                case "zset" -> async.zcard(name);
                case "list" -> async.llen(name);
                default -> async.dump(name); // stream
            });
        }
        connection.flushCommands();
        List<BatchKey> pending = new ArrayList<>();
        for (BatchKey key : group) {
            if ("stream".equals(key.type)) {
                byte[] dump = (byte[]) await(counts.get(key));
                if (dump == null || dump.length > task.maxDeepCompareBytes())
                    key.degradedReason = "DUMP_LIMIT";
                else
                    key.sha.update(dump);
                continue;
            }
            Long count = (Long) await(counts.get(key));
            key.count = count == null ? 0 : count;
            key.sha.update(longBytes(key.count));
            if (key.count > task.maxElementsPerKey())
                key.degradedReason = "MAX_ELEMENTS";
            else if (key.count > 0)
                pending.add(key);
        }
        while (!pending.isEmpty()) {
            Map<BatchKey, RedisFuture<?>> round = new LinkedHashMap<>();
            for (BatchKey key : pending) {
                byte[] name = keys.get(key.position);
                ScanArgs args = ScanArgs.Builder.limit(SCAN_PAGE);
                round.put(key, switch (key.type) {
                    case "string" -> async.getrange(name, key.offset,
                            Math.min(key.count - 1, key.offset + task.chunkBytes() - 1));
                    case "hash" -> async.hscan(name, ScanCursor.of(key.cursor), args);
                    case "set" -> async.sscan(name, ScanCursor.of(key.cursor), args);
                    case "zset" -> async.zscan(name, ScanCursor.of(key.cursor), args);
                    case "list" -> async.lrange(name, key.offset, Math.min(key.count - 1, key.offset + LIST_PAGE - 1));
                    default -> throw new IllegalStateException("unexpected type " + key.type);
                });
            }
            connection.flushCommands();
            List<BatchKey> next = new ArrayList<>();
            for (BatchKey key : pending) {
                Object reply = await(round.get(key));
                switch (key.type) {
                    case "string" -> {
                        byte[] chunk = (byte[]) reply;
                        key.sha.update(chunk == null ? new byte[0] : chunk);
                        key.offset += task.chunkBytes();
                        if (key.offset < key.count)
                            next.add(key);
                    }
                    case "hash" -> {
                        MapScanCursor<byte[], byte[]> page = cast(reply);
                        page.getMap().forEach((field, value) -> xorFold(key.xor, sha256Bytes(field, value)));
                        if (!page.isFinished()) {
                            key.cursor = page.getCursor();
                            next.add(key);
                        }
                    }
                    case "set" -> {
                        ValueScanCursor<byte[]> page = cast(reply);
                        for (byte[] member : page.getValues())
                            xorFold(key.xor, sha256Bytes(member));
                        if (!page.isFinished()) {
                            key.cursor = page.getCursor();
                            next.add(key);
                        }
                    }
                    case "zset" -> {
                        ScoredValueScanCursor<byte[]> page = cast(reply);
                        for (ScoredValue<byte[]> scored : page.getValues())
                            xorFold(key.xor, sha256Bytes(scored.getValue(), scoreBytes(scored.getScore())));
                        if (!page.isFinished()) {
                            key.cursor = page.getCursor();
                            next.add(key);
                        }
                    }
                    case "list" -> {
                        List<byte[]> elements = cast(reply);
                        for (byte[] element : elements) {
                            key.sha.update(longBytes(element.length));
                            key.sha.update(element);
                        }
                        key.offset += LIST_PAGE;
                        if (key.offset < key.count)
                            next.add(key);
                    }
                    default -> throw new IllegalStateException("unexpected type " + key.type);
                }
            }
            pending = next;
        }
        for (BatchKey key : group) {
            if ("hash".equals(key.type) || "set".equals(key.type) || "zset".equals(key.type))
                key.sha.update(key.xor);
            String digest = key.degradedReason == null ? HexFormat.of().formatHex(key.sha.digest()) : null;
            out[key.position] = Optional.of(new ValidationValue(key.type, key.size, key.ttl, digest,
                    digest == null ? "METADATA" : "SEMANTIC_DIGEST", key.degradedReason));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static final class BatchKey {
        final int position;
        final String type;
        final long ttl;
        final long size;
        /** bytes for string, elements for hash/set/zset, length for list — bound into the digest. */
        long count;
        long offset;
        String cursor = "0";
        final byte[] xor = new byte[32];
        final MessageDigest sha = sha256();
        String degradedReason;
        BatchKey(int position, String type, long ttl, long size) {
            this.position = position;
            this.type = type;
            this.ttl = ttl;
            this.size = size;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] sha256Bytes(byte[]... values) {
        MessageDigest digest = sha256();
        for (byte[] value : values)
            digest.update(value);
        return digest.digest();
    }

    private static void xorFold(byte[] fold, byte[] hash) {
        for (int index = 0; index < fold.length; index++)
            fold[index] ^= hash[index];
    }

    private static byte[] longBytes(long value) {
        byte[] out = new byte[8];
        for (int index = 7; index >= 0; index--) {
            out[index] = (byte) value;
            value >>>= 8;
        }
        return out;
    }

    /** Canonical score text, so 1 / 1.0 / +1 score formatting differences never read as value drift. */
    private static byte[] scoreBytes(double score) {
        String text = score == Math.rint(score) && !Double.isInfinite(score) && Math.abs(score) < 9.0e18
                ? String.valueOf((long) score)
                : new BigDecimal(score).stripTrailingZeros().toPlainString();
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static Object await(RedisFuture<?> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("validation pipeline interrupted", error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime)
                throw runtime;
            throw new IllegalStateException("validation pipeline failed", error.getCause());
        }
    }

    @Override
    public TtlApplyResult applyTtlIfUnchanged(long clusterId, int database, byte[] key, long expectedTtlSeconds,
            long targetTtlSeconds) {
        return withCommands(clusterId, database, commands -> {
            // raw Redis TTL semantics: -2 key missing, -1 no expiry. expectedTtlSeconds compares verbatim,
            // so governance's "apply when the key has no expiry" passes expectedTtlSeconds=-1 — normalizing
            // away the sign would make that CAS unsatisfiable.
            Long observed = commands.ttl(key);
            long ttl = observed == null ? -2 : observed;
            if (ttl == -2 || ttl != expectedTtlSeconds)
                return new TtlApplyResult(ttl, false, true);
            boolean applied = Boolean.TRUE.equals(commands.expire(key, targetTtlSeconds));
            return new TtlApplyResult(ttl, applied, !applied);
        });
    }

    @Override
    public boolean unlinkIfPresent(long clusterId, int database, byte[] key) {
        return withCommands(clusterId, database, commands -> commands.unlink(key) > 0);
    }

    @Override
    public List<TtlApplyResult> applyTtlIfUnchangedBatch(long clusterId, int database, List<byte[]> keys,
            long expectedTtlSeconds, long targetTtlSeconds) {
        if (keys.isEmpty())
            return List.of();
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            TtlApplyResult[] out = new TtlApplyResult[keys.size()];
            // same per-master grouping as inspectBatch so a pipeline only carries same-node commands
            if (profile.mode() == ClusterMode.CLUSTER)
                for (Map.Entry<String, List<Integer>> group : slotsByEndpoint(profile, database, keys).entrySet())
                    applyEndpointBatch(profile, group.getKey(), database, keys, group.getValue(),
                            expectedTtlSeconds, targetTtlSeconds, out);
            else
                applyEndpointBatch(profile, profile.seedEndpoints().get(0), database, keys,
                        IntStream.range(0, keys.size()).boxed().toList(), expectedTtlSeconds, targetTtlSeconds, out);
            return Arrays.asList(out);
        }
    }

    private void applyEndpointBatch(RedisConnectionProfile profile, String endpoint, int database,
            List<byte[]> keys, List<Integer> positions, long expectedTtlSeconds, long targetTtlSeconds,
            TtlApplyResult[] out) {
        try {
            endpointApplyBatch(profile, endpoint, database, keys, positions, expectedTtlSeconds, targetTtlSeconds,
                    out);
        } catch (RuntimeException error) {
            if (!transientError(error))
                throw error;
            endpointApplyBatch(profile, endpoint, database, keys, positions, expectedTtlSeconds, targetTtlSeconds,
                    out);
        }
    }

    /**
     * Two pipelined rounds: TTL for the whole page, then EXPIRE only where the raw TTL still matches the
     * expected precondition — the same read-then-write CAS as the per-key form, batched.
     */
    private void endpointApplyBatch(RedisConnectionProfile profile, String endpoint, int database,
            List<byte[]> keys, List<Integer> positions, long expectedTtlSeconds, long targetTtlSeconds,
            TtlApplyResult[] out) {
        try (StatefulRedisConnection<byte[], byte[]> connection = pipelineClient()
                .connect(ByteArrayCodec.INSTANCE, uri(endpoint, database, profile))) {
            connection.setTimeout(Duration.ofSeconds(120));
            connection.setAutoFlushCommands(false);
            RedisAsyncCommands<byte[], byte[]> async = connection.async();
            List<RedisFuture<Long>> ttls = new ArrayList<>(positions.size());
            for (int position : positions)
                ttls.add(async.ttl(keys.get(position)));
            connection.flushCommands();
            List<Integer> applyPositions = new ArrayList<>();
            List<RedisFuture<Boolean>> expires = new ArrayList<>();
            for (int index = 0; index < positions.size(); index++) {
                int position = positions.get(index);
                Long observed = (Long) await(ttls.get(index));
                long ttl = observed == null ? -2 : observed;
                if (ttl == -2 || ttl != expectedTtlSeconds) {
                    out[position] = new TtlApplyResult(ttl, false, true);
                    continue;
                }
                applyPositions.add(position);
                expires.add(async.expire(keys.get(position), targetTtlSeconds));
            }
            connection.flushCommands();
            for (int index = 0; index < applyPositions.size(); index++) {
                int position = applyPositions.get(index);
                boolean applied = Boolean.TRUE.equals((Boolean) await(expires.get(index)));
                out[position] = new TtlApplyResult(expectedTtlSeconds, applied, !applied);
            }
        }
    }

    @Override
    public List<Boolean> unlinkIfPresentBatch(long clusterId, int database, List<byte[]> keys) {
        if (keys.isEmpty())
            return List.of();
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            Boolean[] out = new Boolean[keys.size()];
            if (profile.mode() == ClusterMode.CLUSTER)
                for (Map.Entry<String, List<Integer>> group : slotsByEndpoint(profile, database, keys).entrySet())
                    unlinkEndpointBatch(profile, group.getKey(), database, keys, group.getValue(), out);
            else
                unlinkEndpointBatch(profile, profile.seedEndpoints().get(0), database, keys,
                        IntStream.range(0, keys.size()).boxed().toList(), out);
            return Arrays.asList(out);
        }
    }

    private void unlinkEndpointBatch(RedisConnectionProfile profile, String endpoint, int database,
            List<byte[]> keys, List<Integer> positions, Boolean[] out) {
        try {
            endpointUnlinkBatch(profile, endpoint, database, keys, positions, out);
        } catch (RuntimeException error) {
            if (!transientError(error))
                throw error;
            endpointUnlinkBatch(profile, endpoint, database, keys, positions, out);
        }
    }

    private void endpointUnlinkBatch(RedisConnectionProfile profile, String endpoint, int database,
            List<byte[]> keys, List<Integer> positions, Boolean[] out) {
        try (StatefulRedisConnection<byte[], byte[]> connection = pipelineClient()
                .connect(ByteArrayCodec.INSTANCE, uri(endpoint, database, profile))) {
            connection.setTimeout(Duration.ofSeconds(120));
            connection.setAutoFlushCommands(false);
            RedisAsyncCommands<byte[], byte[]> async = connection.async();
            List<RedisFuture<Long>> unlinks = new ArrayList<>(positions.size());
            for (int position : positions)
                unlinks.add(async.unlink(keys.get(position)));
            connection.flushCommands();
            for (int index = 0; index < positions.size(); index++)
                out[positions.get(index)] = ((Long) await(unlinks.get(index))) > 0;
        }
    }
    private Optional<ValidationValue> inspect(RedisKeyCommands<byte[], byte[]> commands,
            RedisServerCommands<byte[], byte[]> server, RedisStringCommands<byte[], byte[]> strings, byte[] key,
            ValidationTask task) {
        String type = commands.type(key);
        if ("none".equals(type))
            return Optional.empty();
        long ttl = normalizeTtl(commands.ttl(key));
        Long memory = server.memoryUsage(key);
        long size = memory == null ? 0 : memory;
        if (size > task.largeKeyThresholdBytes())
            return Optional.of(new ValidationValue(type, size, ttl, null, "METADATA", "LARGE_KEY_THRESHOLD"));
        if (!("string".equals(type) || "hash".equals(type) || "list".equals(type) || "set".equals(type)
                || "zset".equals(type) || "stream".equals(type)))
            return Optional.of(new ValidationValue(type, size, ttl, null, "METADATA", "UNSUPPORTED_TYPE"));
        if (size > task.maxDeepCompareBytes())
            return Optional.of(new ValidationValue(type, size, ttl, null, "METADATA", "MAX_DEEP_COMPARE_BYTES"));
        String digest = "string".equals(type)
                ? stringDigest(strings, key, task.chunkBytes())
                : dumpDigest(commands, type, key, task.maxDeepCompareBytes());
        if (digest == null)
            return Optional.of(new ValidationValue(type, size, ttl, null, "METADATA", "DUMP_LIMIT"));
        String typeAfter = commands.type(key);
        Long memoryAfter = server.memoryUsage(key);
        if (!type.equals(typeAfter) || !Objects.equals(memory, memoryAfter))
            return Optional.of(new ValidationValue(type, size, ttl, null, "METADATA", "CHANGED_DURING_READ"));
        return Optional.of(new ValidationValue(type, size, ttl, digest, "SEMANTIC_DIGEST", null));
    }

    private <T> T withCommands(long clusterId, int database, CommandCallback<T> callback) {
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            return withSyncCommands(profile, database, commands -> callback.execute(commands.keys()));
        }
    }
    /**
     * Runs the action inside the pool wrapper so a dropped connection is retried once on a fresh pool entry —
     * the I/O must happen within {@link #withPooledBytes}, handing out the pooled object alone would leave
     * command calls on it outside the eviction/retry path.
     */
    private <T> T withSyncCommands(RedisConnectionProfile profile, int database,
            Function<SyncCommands, T> action) {
        if (profile.mode() == ClusterMode.CLUSTER)
            return withPooledBytes("cluster:" + profile.clusterId() + ":" + database,
                    () -> clusterBytes(profile, database), action);
        String endpoint = profile.seedEndpoints().get(0);
        return withPooledBytes(bytesKey(endpoint, database), () -> bytes(profile, endpoint, database), action);
    }
    private ScanPage scanEndpoint(RedisConnectionProfile profile, String endpoint, int database, String cursor,
            int count) {
        return withPooledBytes(bytesKey(endpoint, database), () -> bytes(profile, endpoint, database),
                commands -> {
                    KeyScanCursor<byte[]> page = "0".equals(cursor)
                            ? commands.keys().scan(ScanArgs.Builder.limit(count))
                            : commands.keys().scan(ScanCursor.of(cursor), ScanArgs.Builder.limit(count));
                    return new ScanPage(page.getCursor(), page.getKeys().stream().map(ValidationKey::new).toList());
                });
    }
    private List<String[]> clusterMasters(RedisConnectionProfile profile, int database) {
        return withPooledText("text:" + profile.clusterId() + ":" + database,
                () -> text(profile, profile.seedEndpoints().get(0), database),
                commands -> masterEndpoints(commands.clusterNodes()));
    }
    private static List<String[]> masterEndpoints(String clusterNodes) {
        return Arrays.stream(clusterNodes.split("\\R")).map(String::trim).filter(line -> !line.isBlank())
                .map(line -> line.split("\\s+"))
                .filter(fields -> fields.length >= 3 && fields[2].contains("master") && !fields[2].contains("fail"))
                .map(fields -> new String[]{fields[0], fields[1].split("@", 2)[0]}).toList();
    }
    private <T> T withPooledBytes(String key, Supplier<Pooled> factory,
            Function<SyncCommands, T> action) {
        try {
            return action.apply(pool.computeIfAbsent(key, unused -> factory.get()).bytes);
        } catch (RuntimeException error) {
            if (!transientError(error))
                throw error;
            return retryAfterEviction(key, factory, pooled -> action.apply(pooled.bytes));
        }
    }
    private <T> T withPooledText(String key, Supplier<Pooled> factory,
            Function<RedisCommands<String, String>, T> action) {
        try {
            return action.apply(pool.computeIfAbsent(key, unused -> factory.get()).text);
        } catch (RuntimeException error) {
            if (!transientError(error))
                throw error;
            return retryAfterEviction(key, factory, pooled -> action.apply(pooled.text));
        }
    }
    /**
     * Connection resets and cluster-failover states (CLUSTERDOWN/TRYAGAIN/LOADING) arrive as one-off
     * blips during hour-long validations; retrying once on a fresh connection rides them out, while
     * ordinary command errors (WRONGTYPE, CROSSSLOT, …) must surface immediately.
     */
    private static boolean transientError(RuntimeException error) {
        if (error instanceof RedisConnectionException)
            return true;
        String message = error.getMessage();
        return error instanceof RedisCommandExecutionException && message != null
                && (message.startsWith("CLUSTERDOWN") || message.startsWith("TRYAGAIN")
                        || message.startsWith("LOADING"));
    }
    private <T> T retryAfterEviction(String key, Supplier<Pooled> factory, Function<Pooled, T> action) {
        Pooled stale = pool.remove(key);
        if (stale != null)
            stale.close();
        return action.apply(pool.computeIfAbsent(key, unused -> factory.get()));
    }
    private static String bytesKey(String endpoint, int database) {
        return "direct:" + endpoint + ":" + database;
    }
    private Pooled clusterBytes(RedisConnectionProfile profile, int database) {
        List<RedisURI> uris = profile.seedEndpoints().stream().map(endpoint -> uri(endpoint, database, profile))
                .toList();
        RedisClusterClient client = RedisClusterClient.create(uris);
        var sync = client.connect(ByteArrayCodec.INSTANCE).sync();
        return new Pooled(client, null, new SyncCommands(sync, sync, sync), null);
    }
    private Pooled bytes(RedisConnectionProfile profile, String endpoint, int database) {
        RedisClient client = RedisClient.create(uri(endpoint, database, profile));
        var sync = client.connect(ByteArrayCodec.INSTANCE).sync();
        return new Pooled(null, client, new SyncCommands(sync, sync, sync), null);
    }
    private Pooled text(RedisConnectionProfile profile, String endpoint, int database) {
        RedisClient client = RedisClient.create(uri(endpoint, database, profile));
        return new Pooled(null, client, null, client.connect().sync());
    }
    private static final class Pooled {
        private final RedisClusterClient clusterClient;
        private final RedisClient client;
        private final SyncCommands bytes;
        private final RedisCommands<String, String> text;
        private Pooled(RedisClusterClient clusterClient, RedisClient client,
                SyncCommands bytes, RedisCommands<String, String> text) {
            this.clusterClient = clusterClient;
            this.client = client;
            this.bytes = bytes;
            this.text = text;
        }
        private void close() {
            if (clusterClient != null)
                clusterClient.shutdown();
            if (client != null)
                client.shutdown();
        }
    }
    private static RedisURI uri(String endpoint, int database, RedisConnectionProfile profile) {
        int separator = endpoint.lastIndexOf(':');
        if (separator <= 0 || separator == endpoint.length() - 1)
            throw new IllegalArgumentException("invalid Redis endpoint");
        String host = endpoint.substring(0, separator);
        int port = Integer.parseInt(endpoint.substring(separator + 1));
        RedisURI uri = profile.mode() == ClusterMode.SENTINEL
                ? RedisURI.Builder.sentinel(host, port, profile.sentinelMasterName()).withDatabase(database).build()
                : RedisURI.Builder.redis(host, port).withDatabase(database).build();
        if (profile.password() != null) {
            if (profile.username() != null && !profile.username().isBlank())
                uri.setUsername(profile.username());
            uri.setPassword(profile.password());
        }
        return uri;
    }
    private static long normalizeTtl(Long ttl) {
        return ttl == null || ttl < 0 ? -1 : ttl;
    }
    private static String stringDigest(RedisStringCommands<byte[], byte[]> commands, byte[] key, int chunkBytes) {
        Long length = commands.strlen(key);
        if (length == null)
            return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("string".getBytes(StandardCharsets.US_ASCII));
            for (long start = 0; start < length; start += chunkBytes) {
                byte[] chunk = commands.getrange(key, start, Math.min(length - 1, start + chunkBytes - 1));
                digest.update(chunk);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private static String dumpDigest(RedisKeyCommands<byte[], byte[]> commands, String type, byte[] key,
            long maxBytes) {
        byte[] dump = commands.dump(key);
        if (dump == null || dump.length > maxBytes)
            return null;
        return sha256(type.getBytes(StandardCharsets.US_ASCII), dump);
    }
    private static String sha256(byte[]... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] value : values)
                digest.update(value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    /**
     * The granular command faces of one pooled sync view. Standalone and cluster connections expose different
     * concrete sync types ({@code RedisCommands} vs {@code RedisAdvancedClusterCommands}), and Lettuce wraps
     * either in a JDK timeout proxy, so the granular interfaces are the only types both can be held as. This
     * record stores the one view under each face — Java has no intersection field type.
     */
    private record SyncCommands(RedisKeyCommands<byte[], byte[]> keys,
            RedisServerCommands<byte[], byte[]> server, RedisStringCommands<byte[], byte[]> strings) {
    }
    @FunctionalInterface
    private interface CommandCallback<T> {
        T execute(RedisKeyCommands<byte[], byte[]> commands);
    }
}
