package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.CommandPlan;
import io.github.susongyan.redisops.worker.protocol.RedisSlot;
import io.github.susongyan.redisops.worker.protocol.RespCodec;
import io.github.susongyan.redisops.worker.protocol.RespProtocolException;
import io.github.susongyan.redisops.worker.protocol.RespValue;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class TargetCommandSession implements AutoCloseable {
    private final Socket socket;
    private final RespCodec codec;
    private final byte[] checkpointKey;
    private final byte[] heartbeatKey;
    private final byte[] fenceKey;
    private final byte[] fullProgressPrefix;
    private final long taskId;

    private static final java.util.Set<String> RESERVED_KEY_TYPES = java.util.Set.of("ckpt", "hb", "fence", "cursor",
            "full_progress");

    public TargetCommandSession(WorkerRedisConnectionProfile profile, int database, long taskId,
            Duration connectTimeout)
            throws IOException {
        this(profile, RedisEndpoint.parse(profile.seedEndpoints().get(0)), database, taskId, connectTimeout);
    }

    public TargetCommandSession(WorkerRedisConnectionProfile profile, RedisEndpoint endpoint, int database,
            long taskId, Duration connectTimeout) throws IOException {
        this(profile, endpoint, database, taskId, connectTimeout, standaloneKeyspace(taskId, "standalone"), true);
    }

    TargetCommandSession(WorkerRedisConnectionProfile profile, RedisEndpoint endpoint, int database,
            long taskId, Duration connectTimeout, String channel) throws IOException {
        this(profile, endpoint, database, taskId, connectTimeout, standaloneKeyspace(taskId, channel), true);
    }

    static TargetCommandSession clusterSlot(WorkerRedisConnectionProfile profile, RedisEndpoint endpoint,
            long taskId, Duration connectTimeout, String channel, int slot) throws IOException {
        byte[] progress = ClusterSlotKeyspace.fullProgress(taskId, channel, slot, 0);
        int separator = lastIndexOf(progress, (byte) ':');
        Keyspace keyspace = new Keyspace(ClusterSlotKeyspace.checkpoint(taskId, channel, slot),
                heartbeatKey(taskId, channel, slot), ClusterSlotKeyspace.fence(taskId, channel, slot),
                java.util.Arrays.copyOf(progress, separator + 1));
        return new TargetCommandSession(profile, endpoint, 0, taskId, connectTimeout, keyspace, false);
    }

    private TargetCommandSession(WorkerRedisConnectionProfile profile, RedisEndpoint endpoint, int database,
            long taskId, Duration connectTimeout, Keyspace keyspace, boolean selectDatabase) throws IOException {
        socket = new Socket();
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()),
                Math.toIntExact(connectTimeout.toMillis()));
        codec = new RespCodec(new BufferedInputStream(socket.getInputStream(), 64 * 1024),
                new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
        checkpointKey = keyspace.checkpoint();
        heartbeatKey = keyspace.heartbeat();
        fenceKey = keyspace.fence();
        fullProgressPrefix = keyspace.fullProgressPrefix();
        this.taskId = taskId;
        authenticate(profile);
        if (selectDatabase)
            expectOk(command("SELECT", Integer.toString(database)), "SELECT");
    }

    public Optional<TargetCheckpoint> checkpoint() throws IOException {
        return checkpoint(command(bytes("GET"), checkpointKey));
    }

    /**
     * Deletes this task's own persistent reserved keys found on the connected node. A fresh start
     * always carries a new full sync epoch and the control plane only starts terminal tasks, so
     * every own-task key here belongs to a finished attempt: its worker is gone, and republished
     * fencing fails any zombie transaction. Recovery must never call this - it continues the
     * current attempt rather than superseding it.
     */
    public long deleteOwnReservedKeys() throws IOException {
        List<byte[]> own = new java.util.ArrayList<>();
        for (byte[] key : scanReservedKeys())
            if (ownedByTask(key, taskId))
                own.add(key);
        return deleteKeys(own);
    }

    void requireMultiKeyVersion(io.github.susongyan.redisops.sync.contract.SyncCommandPolicy policy)
            throws IOException {
        int previousTimeout = socket.getSoTimeout();
        socket.setSoTimeout(3000);
        try {
            RespValue response = command("INFO", "server");
            if (response instanceof RespValue.Bulk bulk) {
                for (String line : new String(bulk.value(), StandardCharsets.US_ASCII).split("\\r?\\n")) {
                    if (line.startsWith("redis_version:")) {
                        String[] components = line.substring("redis_version:".length()).split("\\.");
                        try {
                            int major = Integer.parseInt(components[0]);
                            int minor = Integer.parseInt(components[1]);
                            if (policy.supportsMultiKeyRedisVersion(major, minor))
                                return;
                        } catch (RuntimeException invalid) {
                            // Fail closed without returning raw server data.
                        }
                        break;
                    }
                }
            }
            throw new SyncBlockedException("BLOCKED_UNSUPPORTED_REDIS_VERSION",
                    "Redis version is outside the selected command policy compatibility range");
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
    }

    Optional<TargetCheckpoint> checkpoint(long taskId, String channel, int slot) throws IOException {
        return checkpoint(command(bytes("GET"), ClusterSlotKeyspace.checkpoint(taskId, channel, slot)));
    }

    Optional<TargetCheckpoint> cursor(long taskId, String channel, int slot) throws IOException {
        return checkpoint(command(bytes("GET"), ClusterSlotKeyspace.cursor(taskId, channel, slot)));
    }

    public long dbsize() throws IOException {
        RespValue response = command("DBSIZE");
        if (!(response instanceof RespValue.IntegerValue value))
            throw new RespProtocolException("DBSIZE returned an unexpected response");
        return value.value();
    }

    /** Non-destructive availability probe: COMMAND INFO reports nil entries for renamed/unknown commands. */
    public boolean commandAvailable(String name) throws IOException {
        RespValue response = command("COMMAND", "INFO", name);
        if (response instanceof RespValue.Array array)
            return !array.values().isEmpty() && !(array.values().get(0) instanceof RespValue.NullValue);
        throw new RespProtocolException("COMMAND INFO returned an unexpected response");
    }

    public Map<String, Long> commandStats() throws IOException {
        RespValue response = command("INFO", "commandstats");
        if (!(response instanceof RespValue.Bulk bulk))
            throw new RespProtocolException("INFO commandstats returned an unexpected response");
        String info = new String(bulk.value(), StandardCharsets.UTF_8);
        Map<String, Long> result = new LinkedHashMap<>();
        for (String line : info.split("\\r?\\n")) {
            if (!line.startsWith("cmdstat_"))
                continue;
            int separator = line.indexOf(':');
            int calls = line.indexOf("calls=", separator);
            if (separator < 0 || calls < 0)
                continue;
            int end = line.indexOf(',', calls);
            String command = line.substring("cmdstat_".length(), separator)
                    .replace('|', ' ').toUpperCase(Locale.ROOT);
            String count = line.substring(calls + "calls=".length(), end < 0 ? line.length() : end);
            result.merge(command, Long.parseLong(count), Long::sum);
        }
        return Map.copyOf(result);
    }

    public Optional<TargetFence> currentFence() throws IOException {
        RespValue value = command(bytes("GET"), fenceKey);
        if (value == RespValue.NullValue.INSTANCE)
            return Optional.empty();
        if (!(value instanceof RespValue.Bulk bulk))
            throw new RespProtocolException("fence GET returned an unexpected response");
        return Optional.of(TargetFence.decode(bulk.value()));
    }

    public void assertReservedNamespaceAvailable() throws IOException {
        if (!foreignReservedKeys().isEmpty())
            throw new SyncBlockedException("BLOCKED_RESERVED_NAMESPACE",
                    "target contains a Redis Ops reserved key owned by another task");
    }

    /** All keys in the Redis Ops reserved namespace on the connected node. */
    private List<byte[]> scanReservedKeys() throws IOException {
        List<byte[]> found = new java.util.ArrayList<>();
        String cursor = "0";
        do {
            RespValue response = command(bytes("SCAN"), bytes(cursor), bytes("MATCH"),
                    bytes("__redis_ops_sync_*"), bytes("COUNT"), bytes("1000"));
            if (!(response instanceof RespValue.Array outer) || outer.values().size() != 2
                    || !(outer.values().get(0) instanceof RespValue.Bulk next)
                    || !(outer.values().get(1) instanceof RespValue.Array keys))
                throw new RespProtocolException("SCAN returned an unexpected response");
            cursor = new String(next.value(), StandardCharsets.US_ASCII);
            for (RespValue value : keys.values()) {
                if (!(value instanceof RespValue.Bulk key))
                    throw new RespProtocolException("SCAN key is not a bulk string");
                found.add(key.value());
            }
        } while (!"0".equals(cursor));
        return found;
    }

    /** Reserved-namespace keys not owned by this task; callers decide whether the owning task is still alive. */
    public List<byte[]> foreignReservedKeys() throws IOException {
        List<byte[]> foreign = new java.util.ArrayList<>();
        for (byte[] key : scanReservedKeys()) {
            if (!java.util.Arrays.equals(key, checkpointKey)
                    && !java.util.Arrays.equals(key, heartbeatKey)
                    && !java.util.Arrays.equals(key, fenceKey)
                    && !startsWith(key, fullProgressPrefix)
                    && !ownedByTask(key, taskId))
                foreign.add(key);
        }
        return foreign;
    }

    public long deleteKeys(List<byte[]> keys) throws IOException {
        long deleted = 0;
        for (byte[] key : keys) {
            // One DEL per key: a cluster target rejects a multi-key DEL whose keys hash to
            // different slots (CROSSSLOT), and reserved keys rarely share a hash tag.
            RespValue response = command(bytes("DEL"), key);
            failOnError(response, "DEL");
            if (response instanceof RespValue.IntegerValue value)
                deleted += value.value();
            else
                throw new RespProtocolException("DEL returned an unexpected response");
        }
        return deleted;
    }

    /**
     * Parses the owning task id out of a reserved key. Standalone keys carry "{taskId}:channel" after the
     * type segment; cluster keys carry "{slotTag}:{taskId}:channel" where the tag is alphanumeric, so a
     * fully numeric tag means the standalone layout. Unparsable keys return empty and stay treated as foreign.
     */
    static java.util.OptionalLong ownerTaskId(byte[] key) {
        String text = new String(key, StandardCharsets.US_ASCII);
        if (!text.startsWith("__redis_ops_sync_"))
            return java.util.OptionalLong.empty();
        int typeEnd = text.indexOf("__", "__redis_ops_sync_".length());
        if (typeEnd < 0 || typeEnd + 3 > text.length()
                || !RESERVED_KEY_TYPES.contains(text.substring("__redis_ops_sync_".length(), typeEnd)))
            return java.util.OptionalLong.empty();
        String rest = text.substring(typeEnd + 3);
        if (!rest.startsWith("{"))
            return java.util.OptionalLong.empty();
        int tagEnd = rest.indexOf('}');
        if (tagEnd < 1)
            return java.util.OptionalLong.empty();
        String tag = rest.substring(1, tagEnd);
        String candidate;
        if (tag.chars().allMatch(Character::isDigit))
            candidate = tag; // standalone "{taskId}:channel"
        else {
            if (!rest.startsWith(":", tagEnd + 1))
                return java.util.OptionalLong.empty();
            int idEnd = rest.indexOf(':', tagEnd + 2);
            candidate = idEnd < 0 ? rest.substring(tagEnd + 2) : rest.substring(tagEnd + 2, idEnd);
        }
        try {
            long owner = Long.parseLong(candidate);
            return owner > 0 ? java.util.OptionalLong.of(owner) : java.util.OptionalLong.empty();
        } catch (NumberFormatException unparsable) {
            return java.util.OptionalLong.empty();
        }
    }

    /**
     * A reserved key belongs to this task when it carries this task's id, regardless of channel
     * or cluster slot tag. Standalone keys carry "{taskId}:channel" after the type segment;
     * cluster keys carry "{slotTag}:{taskId}:channel". Ownership by taskId alone lets a re-run
     * of the same task skip its own leftover metadata without opening the namespace to other
     * tasks.
     */
    static boolean ownedByTask(byte[] key, long taskId) {
        String text = new String(key, StandardCharsets.US_ASCII);
        if (!text.startsWith("__redis_ops_sync_"))
            return false;
        int typeEnd = text.indexOf("__", "__redis_ops_sync_".length());
        if (typeEnd < 0 || typeEnd + 3 > text.length()
                || !RESERVED_KEY_TYPES.contains(text.substring("__redis_ops_sync_".length(), typeEnd)))
            return false;
        String rest = text.substring(typeEnd + 3);
        if (rest.startsWith("{" + taskId + "}:"))
            return true;
        if (!rest.startsWith("{"))
            return false;
        int tagEnd = rest.indexOf('}');
        return tagEnd > 0 && rest.startsWith(":" + taskId + ":", tagEnd + 1);
    }

    public void restore(byte[] key, long absoluteExpireMillis, byte[] payload) throws IOException {
        long ttl = absoluteExpireMillis < 0 ? 0 : absoluteExpireMillis;
        expectOk(command(bytes("RESTORE"), key, bytes(Long.toString(ttl)), payload, bytes("REPLACE"),
                bytes("ABSTTL")), "RESTORE");
    }

    public void restoreBatch(List<RestoreRequest> requests, TargetFence expectedFence, int lane,
            LeaseGuard leaseGuard) throws IOException {
        restoreBatch(requests, expectedFence, lane, leaseGuard, fenceKey, fullProgressPrefix);
    }

    void restoreBatch(List<RestoreRequest> requests, TargetFence expectedFence, int lane,
            LeaseGuard leaseGuard, long taskId, String channel, int slot) throws IOException {
        byte[] progress = ClusterSlotKeyspace.fullProgress(taskId, channel, slot, 0);
        restoreBatch(requests, expectedFence, lane, leaseGuard,
                ClusterSlotKeyspace.fence(taskId, channel, slot),
                java.util.Arrays.copyOf(progress, lastIndexOf(progress, (byte) ':') + 1));
    }

    private void restoreBatch(List<RestoreRequest> requests, TargetFence expectedFence, int lane,
            LeaseGuard leaseGuard, byte[] operationFenceKey, byte[] operationProgressPrefix)
            throws IOException {
        leaseGuard.assertValid();
        expectOk(command(bytes("WATCH"), operationFenceKey), "WATCH");
        try {
            assertFenceValue(command(bytes("GET"), operationFenceKey), expectedFence);
            leaseGuard.assertValid();
            expectOk(command("MULTI"), "MULTI");
            for (RestoreRequest request : requests) {
                long ttl = request.absoluteExpireMillis() < 0 ? 0 : request.absoluteExpireMillis();
                codec.writeCommandBuffered(bytes("RESTORE"), request.key(), bytes(Long.toString(ttl)),
                        request.payload(), bytes("REPLACE"), bytes("ABSTTL"));
            }
            byte[] progressKey = concat(operationProgressPrefix, bytes(Integer.toString(lane)));
            byte[] progress = bytes(expectedFence.generation() + "|" + requests.size() + "|"
                    + Instant.now().toEpochMilli());
            codec.writeCommandBuffered(bytes("SET"), progressKey, progress);
            codec.flush();
            for (int i = 0; i < requests.size() + 1; i++)
                expectQueued(codec.read());
            leaseGuard.assertValid();
            RespValue result = command("EXEC");
            if (result == RespValue.NullValue.INSTANCE)
                throw new FencingException("full restore batch lost the target fence");
            assertTransaction(result, "full restore transaction");
        } catch (RuntimeException | IOException error) {
            unwatchQuietly();
            throw error;
        }
    }

    /**
     * Restores a multi-slot batch that is guaranteed to live on one target node. Cluster-mode
     * WATCH is single-slot, so every distinct slot forms its own WATCH/GET/MULTI/RESTORE/EXEC
     * transaction. The transactions run in two pipelined phases on this node connection: first
     * every slot's WATCH and fence GET (so a lost fence is detected before any write is sent),
     * then every slot's MULTI/RESTORE/EXEC. A node batch therefore costs two round trips no
     * matter how many slots or keys it spans, with the same fencing semantics as the per-slot
     * path. No progress key is written here: the authoritative full-sync progress lives in the
     * control database. Any failed command leaves the reply stream partially consumed, so the
     * connection is only fit for reconnect afterwards (the task fails and the runner is
     * discarded either way).
     */
    void restoreNodeBatch(List<RestoreRequest> requests, TargetFence expectedFence, int lane,
            LeaseGuard leaseGuard, long taskId, String channel) throws IOException {
        leaseGuard.assertValid();
        Map<Integer, List<RestoreRequest>> bySlot = new LinkedHashMap<>();
        for (RestoreRequest request : requests)
            bySlot.computeIfAbsent(RedisSlot.of(request.key()), ignored -> new ArrayList<>()).add(request);
        try {
            // Phase 1: watch every slot and read its fence back before writing anything.
            for (Integer slot : bySlot.keySet()) {
                byte[] fenceKey = ClusterSlotKeyspace.fence(taskId, channel, slot);
                codec.writeCommandBuffered(bytes("WATCH"), fenceKey);
                codec.writeCommandBuffered(bytes("GET"), fenceKey);
            }
            codec.flush();
            for (Integer slot : bySlot.keySet()) {
                expectOk(codec.read(), "WATCH");
                assertFenceValue(codec.read(), expectedFence);
            }
            leaseGuard.assertValid();
            // Phase 2: queue the restores and execute each slot's transaction.
            for (List<RestoreRequest> slotRequests : bySlot.values()) {
                codec.writeCommandBuffered(bytes("MULTI"));
                for (RestoreRequest request : slotRequests) {
                    long ttl = request.absoluteExpireMillis() < 0 ? 0 : request.absoluteExpireMillis();
                    codec.writeCommandBuffered(bytes("RESTORE"), request.key(), bytes(Long.toString(ttl)),
                            request.payload(), bytes("REPLACE"), bytes("ABSTTL"));
                }
                codec.writeCommandBuffered(bytes("EXEC"));
            }
            codec.flush();
            for (List<RestoreRequest> slotRequests : bySlot.values()) {
                expectOk(codec.read(), "MULTI");
                for (int i = 0; i < slotRequests.size(); i++)
                    expectQueued(codec.read());
                RespValue result = codec.read();
                if (result == RespValue.NullValue.INSTANCE)
                    throw new FencingException("full restore batch lost the target fence");
                assertTransaction(result, "full restore transaction");
            }
        } catch (RuntimeException | IOException error) {
            unwatchQuietly();
            throw error;
        }
    }

    public void writeHeartbeat(long timestampMillis) throws IOException {
        expectOk(command(bytes("SET"), heartbeatKey, bytes(Long.toString(timestampMillis)),
                bytes("PX"), bytes("60000")), "SET heartbeat");
    }

    public void deleteHeartbeat() throws IOException {
        failOnError(command(bytes("DEL"), heartbeatKey), "DEL heartbeat");
    }

    public void loadFunction(byte[] payload, TargetFence expectedFence, LeaseGuard leaseGuard) throws IOException {
        leaseGuard.assertValid();
        expectOk(command(bytes("WATCH"), fenceKey), "WATCH");
        try {
            assertFenceValue(command(bytes("GET"), fenceKey), expectedFence);
            expectOk(command("MULTI"), "MULTI");
            expectQueued(command(bytes("FUNCTION"), bytes("LOAD"), bytes("REPLACE"), payload));
            leaseGuard.assertValid();
            RespValue result = command("EXEC");
            if (result == RespValue.NullValue.INSTANCE)
                throw new FencingException("function load lost the target fence");
            assertTransaction(result, "FUNCTION LOAD");
        } catch (RuntimeException | IOException error) {
            unwatchQuietly();
            throw error;
        }
    }

    public FencePublication publishFence(TargetFence requested, LeaseGuard leaseGuard) throws IOException {
        return publishFence(requested, leaseGuard, fenceKey, checkpointKey);
    }

    FencePublication publishFence(TargetFence requested, LeaseGuard leaseGuard, long taskId,
            String channel, int slot) throws IOException {
        return publishFence(requested, leaseGuard, ClusterSlotKeyspace.fence(taskId, channel, slot),
                ClusterSlotKeyspace.checkpoint(taskId, channel, slot));
    }

    FencePublication publishCursorFence(TargetFence requested, LeaseGuard leaseGuard, long taskId,
            String channel, int slot) throws IOException {
        return publishFence(requested, leaseGuard, ClusterSlotKeyspace.fence(taskId, channel, slot),
                ClusterSlotKeyspace.cursor(taskId, channel, slot));
    }

    private FencePublication publishFence(TargetFence requested, LeaseGuard leaseGuard,
            byte[] operationFenceKey, byte[] operationCheckpointKey) throws IOException {
        for (int attempt = 0; attempt < 20; attempt++) {
            leaseGuard.assertValid();
            expectOk(command(bytes("WATCH"), operationFenceKey, operationCheckpointKey), "WATCH");
            Optional<TargetFence> existingFence = fence(command(bytes("GET"), operationFenceKey));
            RespValue checkpointValue = command(bytes("GET"), operationCheckpointKey);
            boolean pending = checkpointValue instanceof RespValue.Bulk bulk
                    && PendingTargetBatch.isPending(bulk.value());
            Optional<TargetCheckpoint> existingCheckpoint = pending ? Optional.empty() : checkpoint(checkpointValue);
            if (existingFence.isPresent()) {
                TargetFence existing = existingFence.get();
                if (!existing.epoch().equals(requested.epoch())) {
                    command("UNWATCH");
                    throw new SyncBlockedException("BLOCKED_RESERVED_NAMESPACE",
                            "target fence belongs to another full sync epoch");
                }
                if (existing.generation() > requested.generation()) {
                    command("UNWATCH");
                    throw new FencingException("target fence belongs to a newer worker generation");
                }
                if (existing.generation() == requested.generation() && !existing.ownedBy(requested)) {
                    command("UNWATCH");
                    throw new FencingException("target fence generation belongs to another runtime");
                }
            }
            if (existingCheckpoint.isPresent()
                    && !existingCheckpoint.get().epoch().equals(requested.epoch())) {
                command("UNWATCH");
                throw new SyncBlockedException("BLOCKED_RESERVED_NAMESPACE",
                        "target checkpoint belongs to another full sync epoch");
            }
            leaseGuard.assertValid();
            expectOk(command("MULTI"), "MULTI");
            expectQueued(command(bytes("SET"), operationFenceKey, requested.encode()));
            RespValue result = command("EXEC");
            if (result == RespValue.NullValue.INSTANCE)
                continue;
            assertTransaction(result, "target fence transaction");
            // Revoke the former writer even when recovery cannot yet continue. Never clear ambiguity.
            if (pending)
                throw PendingTargetBatch.unresolved();
            return new FencePublication(requested, existingCheckpoint);
        }
        throw new IllegalStateException("target fence transaction remained contended");
    }

    public TargetCheckpoint apply(List<CommandPlan.PlannedCommand> commands, TargetCheckpoint next,
            TargetFence expectedFence, LeaseGuard leaseGuard)
            throws IOException {
        return apply(commands, next, expectedFence, leaseGuard, fenceKey, checkpointKey);
    }

    TargetCheckpoint apply(List<CommandPlan.PlannedCommand> commands, TargetCheckpoint next,
            TargetFence expectedFence, LeaseGuard leaseGuard, long taskId, String channel, int slot)
            throws IOException {
        return apply(commands, next, expectedFence, leaseGuard,
                ClusterSlotKeyspace.fence(taskId, channel, slot),
                ClusterSlotKeyspace.checkpoint(taskId, channel, slot));
    }

    TargetCheckpoint applyCursor(TargetCheckpoint next, TargetFence expectedFence, LeaseGuard leaseGuard,
            long taskId, String channel, int slot) throws IOException {
        return apply(List.of(), next, expectedFence, leaseGuard,
                ClusterSlotKeyspace.fence(taskId, channel, slot),
                ClusterSlotKeyspace.cursor(taskId, channel, slot));
    }

    private TargetCheckpoint apply(List<CommandPlan.PlannedCommand> commands, TargetCheckpoint next,
            TargetFence expectedFence, LeaseGuard leaseGuard, byte[] operationFenceKey,
            byte[] operationCheckpointKey) throws IOException {
        for (var planned : commands) {
            String name = new String(planned.arguments().get(0), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            if (io.github.susongyan.redisops.sync.contract.SyncCommandCapabilities.destructive(name))
                throw new SyncBlockedException("BLOCKED_DESTRUCTIVE_BATCH_CONFIRMATION",
                        "database-wide deletion would erase the batch confirmation state");
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            leaseGuard.assertValid();
            expectOk(command(bytes("WATCH"), operationFenceKey, operationCheckpointKey), "WATCH");
            assertFenceValue(command(bytes("GET"), operationFenceKey), expectedFence);
            Optional<TargetCheckpoint> current = checkpoint(command(bytes("GET"), operationCheckpointKey));
            TargetCheckpoint valueToWrite = next;
            if (current.isPresent()) {
                TargetCheckpoint existing = current.get();
                if (!existing.epoch().equals(next.epoch())) {
                    command("UNWATCH");
                    throw new SyncBlockedException("BLOCKED_RESERVED_NAMESPACE",
                            "target checkpoint belongs to another full sync epoch");
                }
                if (existing.generation() > next.generation()) {
                    command("UNWATCH");
                    throw new FencingException("target checkpoint belongs to a newer worker generation");
                }
                if (existing.appliedOffset() > next.appliedOffset()) {
                    command("UNWATCH");
                    return existing;
                }
                if (existing.appliedOffset() == next.appliedOffset()) {
                    if (existing.generation() == next.generation()) {
                        command("UNWATCH");
                        return existing;
                    }
                    valueToWrite = new TargetCheckpoint(existing.epoch(), next.generation(),
                            existing.replicationId(), existing.appliedOffset(), existing.sourceDatabase(),
                            Instant.now());
                    commands = List.of();
                }
            }
            if (commands.isEmpty()) {
                leaseGuard.assertValid();
                expectOk(command("MULTI"), "MULTI");
                expectQueued(command(bytes("SET"), operationCheckpointKey, valueToWrite.encode()));
                leaseGuard.assertValid();
                RespValue result = command("EXEC");
                if (result == RespValue.NullValue.INSTANCE)
                    continue;
                assertTransaction(result, "target cursor transaction");
                return valueToWrite;
            }
            // Persist ambiguity BEFORE sending effects. Old workers cannot decode this format either.
            byte[] pending = PendingTargetBatch.encode(current.orElse(null), valueToWrite);
            leaseGuard.assertValid();
            expectOk(command("MULTI"), "MULTI");
            expectQueued(command(bytes("SET"), operationCheckpointKey, pending));
            leaseGuard.assertValid();
            RespValue prepared = command("EXEC");
            if (prepared == RespValue.NullValue.INSTANCE)
                continue;
            assertTransaction(prepared, "target prepare transaction");
            try {
                watchPending(operationFenceKey, operationCheckpointKey, pending, expectedFence, leaseGuard);
                expectOk(command("MULTI"), "MULTI");
                for (CommandPlan.PlannedCommand planned : commands)
                    codec.writeCommandBuffered(planned.arguments().toArray(byte[][]::new));
                codec.flush();
                for (int i = 0; i < commands.size(); i++)
                    expectQueued(codec.read());
                leaseGuard.assertValid();
                RespValue applied = command("EXEC");
                if (!(applied instanceof RespValue.Array array) || array.values().size() != commands.size())
                    throw PendingTargetBatch.unresolved();
                assertTransaction(applied, "target transaction");
                // Only this live caller has inspected every reply. A restarted caller must not guess.
                watchPending(operationFenceKey, operationCheckpointKey, pending, expectedFence, leaseGuard);
                expectOk(command("MULTI"), "MULTI");
                expectQueued(command(bytes("SET"), operationCheckpointKey, valueToWrite.encode()));
                leaseGuard.assertValid();
                RespValue confirmed = command("EXEC");
                if (confirmed == RespValue.NullValue.INSTANCE)
                    throw PendingTargetBatch.unresolved();
                assertTransaction(confirmed, "target confirm transaction");
                return valueToWrite;
            } catch (IOException | RuntimeException error) {
                // Never reuse a connection which may still be inside MULTI, or expose raw Redis errors.
                close();
                throw PendingTargetBatch.unresolved();
            }
        }
        throw new IllegalStateException("target checkpoint transaction remained contended");
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort during shutdown.
        }
    }

    private void watchPending(byte[] operationFenceKey, byte[] operationCheckpointKey, byte[] pending,
            TargetFence expectedFence, LeaseGuard leaseGuard) throws IOException {
        leaseGuard.assertValid();
        expectOk(command(bytes("WATCH"), operationFenceKey, operationCheckpointKey), "WATCH");
        assertFenceValue(command(bytes("GET"), operationFenceKey), expectedFence);
        RespValue stored = command(bytes("GET"), operationCheckpointKey);
        if (!(stored instanceof RespValue.Bulk bulk) || !java.util.Arrays.equals(pending, bulk.value()))
            throw PendingTargetBatch.unresolved();
    }

    private void authenticate(WorkerRedisConnectionProfile profile) throws IOException {
        if (profile.password() == null)
            return;
        byte[] password = encode(profile.password());
        try {
            if (profile.username() == null || profile.username().isBlank())
                expectOk(command(bytes("AUTH"), password), "AUTH");
            else
                expectOk(command(bytes("AUTH"), bytes(profile.username()), password), "AUTH");
        } finally {
            java.util.Arrays.fill(password, (byte) 0);
        }
    }

    private RespValue command(String... arguments) throws IOException {
        codec.writeCommand(arguments);
        return codec.read();
    }

    private RespValue command(byte[]... arguments) throws IOException {
        codec.writeCommand(arguments);
        return codec.read();
    }

    private Optional<TargetCheckpoint> checkpoint(RespValue value) {
        if (value == RespValue.NullValue.INSTANCE)
            return Optional.empty();
        if (!(value instanceof RespValue.Bulk bulk))
            throw new RespProtocolException("checkpoint GET returned an unexpected response");
        return Optional.of(TargetCheckpoint.decode(bulk.value()));
    }

    private Optional<TargetFence> fence(RespValue value) {
        if (value == RespValue.NullValue.INSTANCE)
            return Optional.empty();
        if (!(value instanceof RespValue.Bulk bulk))
            throw new RespProtocolException("fence GET returned an unexpected response: "
                    + value.getClass().getSimpleName());
        return Optional.of(TargetFence.decode(bulk.value()));
    }

    private void assertFenceValue(RespValue value, TargetFence expected) {
        Optional<TargetFence> actual = fence(value);
        if (actual.isEmpty() || !actual.get().ownedBy(expected))
            throw new FencingException("target fence no longer belongs to this worker runtime");
    }

    private void unwatchQuietly() {
        try {
            command("UNWATCH");
        } catch (Exception ignored) {
            // The connection is closed by the caller when its fencing operation fails.
        }
    }

    private static void assertTransaction(RespValue result, String operation) {
        if (!(result instanceof RespValue.Array array))
            throw new RespProtocolException("EXEC returned an unexpected response");
        for (RespValue item : array.values())
            failOnError(item, operation);
    }

    private static void expectOk(RespValue value, String command) {
        if (value instanceof RespValue.Simple simple && "OK".equalsIgnoreCase(simple.value()))
            return;
        failOnError(value, command);
        throw new RespProtocolException(command + " did not return OK");
    }

    private static void expectQueued(RespValue value) {
        if (value instanceof RespValue.Simple simple && "QUEUED".equalsIgnoreCase(simple.value()))
            return;
        failOnError(value, "queued command");
        throw new RespProtocolException("target command was not queued");
    }

    private static void failOnError(RespValue value, String command) {
        if (value instanceof RespValue.Error error)
            throw new RespProtocolException(command + " failed: " + error.value());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encode(char[] value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            if (encoded.hasArray())
                java.util.Arrays.fill(encoded.array(), (byte) 0);
            return result;
        } catch (Exception error) {
            throw new RespProtocolException("cannot encode Redis password", error);
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length)
            return false;
        for (int i = 0; i < prefix.length; i++)
            if (value[i] != prefix[i])
                return false;
        return true;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = java.util.Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static Keyspace standaloneKeyspace(long taskId, String channel) {
        String safeChannel = safeChannel(channel);
        return new Keyspace(
                bytes("__redis_ops_sync_ckpt__:{" + taskId + "}:" + safeChannel),
                bytes("__redis_ops_sync_hb__:{" + taskId + "}:" + safeChannel),
                bytes("__redis_ops_sync_fence__:{" + taskId + "}:" + safeChannel),
                bytes("__redis_ops_sync_full_progress__:{" + taskId + "}:" + safeChannel + ":"));
    }

    private static byte[] heartbeatKey(long taskId, String channel, int slot) {
        return ClusterSlotKeyspace.heartbeat(taskId, safeChannel(channel), slot);
    }

    private static String safeChannel(String channel) {
        if (channel == null || channel.isBlank() || !channel.matches("[A-Za-z0-9._-]+"))
            throw new IllegalArgumentException("invalid sync channel");
        return channel;
    }

    private static int lastIndexOf(byte[] value, byte expected) {
        for (int i = value.length - 1; i >= 0; i--)
            if (value[i] == expected)
                return i;
        throw new IllegalArgumentException("full progress key has no separator");
    }

    private record Keyspace(byte[] checkpoint, byte[] heartbeat, byte[] fence, byte[] fullProgressPrefix) {
        private Keyspace {
            checkpoint = checkpoint.clone();
            heartbeat = heartbeat.clone();
            fence = fence.clone();
            fullProgressPrefix = fullProgressPrefix.clone();
        }
    }

    public record FencePublication(TargetFence fence, Optional<TargetCheckpoint> checkpoint) {
    }

    public record RestoreRequest(byte[] key, long absoluteExpireMillis, byte[] payload) {
        public RestoreRequest {
            key = key.clone();
            payload = payload.clone();
        }

        @Override
        public byte[] key() {
            return key.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    public static final class FencingException extends IllegalStateException {
        public FencingException(String message) {
            super(message);
        }
    }
}
