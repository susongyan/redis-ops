package io.github.redisops.sync.engine;

import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.sync.protocol.CommandPlan;
import io.github.redisops.sync.protocol.RespCodec;
import io.github.redisops.sync.protocol.RespProtocolException;
import io.github.redisops.sync.protocol.RespValue;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class TargetCommandSession implements AutoCloseable {
    private final Socket socket;
    private final RespCodec codec;
    private final byte[] checkpointKey;
    private final byte[] heartbeatKey;
    private final byte[] fenceKey;
    private final byte[] fullProgressPrefix;

    public TargetCommandSession(RedisConnectionProfile profile, int database, long taskId, Duration connectTimeout)
            throws IOException {
        this(profile, RedisEndpoint.parse(profile.seedEndpoints().get(0)), database, taskId, connectTimeout);
    }

    public TargetCommandSession(RedisConnectionProfile profile, RedisEndpoint endpoint, int database,
            long taskId, Duration connectTimeout) throws IOException {
        this(profile, endpoint, database, connectTimeout, standaloneKeyspace(taskId, "standalone"), true);
    }

    TargetCommandSession(RedisConnectionProfile profile, RedisEndpoint endpoint, int database,
            long taskId, Duration connectTimeout, String channel) throws IOException {
        this(profile, endpoint, database, connectTimeout, standaloneKeyspace(taskId, channel), true);
    }

    static TargetCommandSession clusterSlot(RedisConnectionProfile profile, RedisEndpoint endpoint,
            long taskId, Duration connectTimeout, String channel, int slot) throws IOException {
        byte[] progress = ClusterSlotKeyspace.fullProgress(taskId, channel, slot, 0);
        int separator = lastIndexOf(progress, (byte) ':');
        Keyspace keyspace = new Keyspace(ClusterSlotKeyspace.checkpoint(taskId, channel, slot),
                heartbeatKey(taskId, channel, slot), ClusterSlotKeyspace.fence(taskId, channel, slot),
                java.util.Arrays.copyOf(progress, separator + 1));
        return new TargetCommandSession(profile, endpoint, 0, connectTimeout, keyspace, false);
    }

    private TargetCommandSession(RedisConnectionProfile profile, RedisEndpoint endpoint, int database,
            Duration connectTimeout, Keyspace keyspace, boolean selectDatabase) throws IOException {
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
        authenticate(profile);
        if (selectDatabase)
            expectOk(command("SELECT", Integer.toString(database)), "SELECT");
    }

    public Optional<TargetCheckpoint> checkpoint() throws IOException {
        return checkpoint(command(bytes("GET"), checkpointKey));
    }

    Optional<TargetCheckpoint> checkpoint(long taskId, String channel, int slot) throws IOException {
        return checkpoint(command(bytes("GET"), ClusterSlotKeyspace.checkpoint(taskId, channel, slot)));
    }

    Optional<TargetCheckpoint> cursor(long taskId, String channel, int slot) throws IOException {
        return checkpoint(command(bytes("GET"), ClusterSlotKeyspace.cursor(taskId, channel, slot)));
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
                if (!java.util.Arrays.equals(key.value(), checkpointKey)
                        && !java.util.Arrays.equals(key.value(), heartbeatKey)
                        && !java.util.Arrays.equals(key.value(), fenceKey)
                        && !startsWith(key.value(), fullProgressPrefix))
                    throw new SyncBlockedException("BLOCKED_RESERVED_NAMESPACE",
                            "target contains a Redis Ops reserved key owned by another task");
            }
        } while (!"0".equals(cursor));
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
            Optional<TargetCheckpoint> existingCheckpoint = checkpoint(command(bytes("GET"),
                    operationCheckpointKey));
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
            leaseGuard.assertValid();
            expectOk(command("MULTI"), "MULTI");
            for (CommandPlan.PlannedCommand planned : commands)
                expectQueued(command(planned.arguments().toArray(byte[][]::new)));
            expectQueued(command(bytes("SET"), operationCheckpointKey, valueToWrite.encode()));
            leaseGuard.assertValid();
            RespValue result = command("EXEC");
            if (result == RespValue.NullValue.INSTANCE)
                continue;
            assertTransaction(result, "target transaction");
            return valueToWrite;
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

    private void authenticate(RedisConnectionProfile profile) throws IOException {
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
            throw new RespProtocolException("fence GET returned an unexpected response");
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
