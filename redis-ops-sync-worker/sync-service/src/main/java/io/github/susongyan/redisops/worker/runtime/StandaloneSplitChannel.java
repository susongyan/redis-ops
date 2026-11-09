package io.github.susongyan.redisops.worker.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Standalone-target split plumbing: one lazy session to the primary, no slot routing. The temp
 * key is prefixed with the private namespace and fingerprints the real key (§6.1).
 */
final class StandaloneSplitChannel implements SplitTargetChannel {
    private static final String TEMP_PREFIX = "__ropstmppart__:";

    private final WorkerRedisConnectionProfile profile;
    private final RedisDataEndpointResolver endpoints;
    private final int database;
    private final long taskId;
    private final String channel;
    private final Duration connectTimeout;
    private final Duration socketTimeout;
    private volatile TargetCommandSession session;

    StandaloneSplitChannel(WorkerRedisConnectionProfile profile, RedisDataEndpointResolver endpoints,
            int database, long taskId, String channel, Duration connectTimeout, Duration socketTimeout) {
        this.profile = profile;
        this.endpoints = endpoints;
        this.database = database;
        this.taskId = taskId;
        this.channel = channel;
        this.connectTimeout = connectTimeout;
        this.socketTimeout = socketTimeout;
    }

    @Override
    public void apply(byte[] slotKey, List<byte[][]> commands, TargetFence fence, LeaseGuard leaseGuard)
            throws IOException {
        session().applySplitBatch(commands, fence, leaseGuard);
    }

    @Override
    public long llen(byte[] key) throws IOException {
        return session().llen(key);
    }

    @Override
    public long exists(byte[] key) throws IOException {
        return session().exists(key);
    }

    @Override
    public long unlink(byte[] key) throws IOException {
        return session().unlink(key);
    }

    @Override
    public long protoMaxBulkLen() throws IOException {
        return session().protoMaxBulkLen();
    }

    @Override
    public void reopen(byte[] slotKey) {
        closeQuietly(session);
        session = null;
    }

    /** Deterministic standalone temp key (§6.1): same key always maps to the same name. */
    @Override
    public byte[] tempKey(long taskId, String channel, byte[] key) {
        return tempKeyOf(taskId, channel, key);
    }

    static byte[] tempKeyOf(long taskId, String channel, byte[] key) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(key);
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
        String hex = HexFormat.of().formatHex(digest);
        return (TEMP_PREFIX + "{" + taskId + "}:" + TargetCommandSession.safeChannel(channel)
                + ":" + hex.substring(0, 16)).getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public void close() {
        closeQuietly(session);
        session = null;
    }

    private TargetCommandSession session() throws IOException {
        if (session == null)
            session = new TargetCommandSession(profile, endpoints.resolvePrimary(profile), database,
                    taskId, connectTimeout, socketTimeout, channel);
        return session;
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null)
            return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // Best effort during reconnect or shutdown.
        }
    }
}
