package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.RedisSlot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Cluster-target split plumbing on top of the sync channel's {@link ClusterTargetRouter}: every
 * operation resolves the key's slot, the router holds one connection per master (shared, and
 * individually synchronized, with the restore lanes) and publishes that slot's fence before the
 * first fenced batch. {@link #reopen} drops the node connection and refreshes the topology, so a
 * MOVED after an in-flight reshard routes the retry to the new owner.
 *
 * <p>
 * Temp-key naming (§6.1, P2 corrected): the name must hash to the real key's slot without reusing
 * the real key verbatim. Wrapping the raw key in braces — the original design sketch — breaks for
 * hash-tagged keys ({@code {user:{123}:k}} would hash the tag {@code user:{123}). The effective
 * hashed substring is wrapped instead, mirroring {@code keyHashSlot} in cluster.c:
 *
 * <ol>
 * <li>key has a valid non-empty tag → {@code {tag}:__ropstmppart__:taskId:channel} (tags never
 * contain {@code '}'}, so the wrap is exact);
 * <li>key hashes whole and contains no {@code '}'} → {@code {key}:__ropstmppart__:taskId:channel};
 * <li>key hashes whole but contains {@code '}'} (no brace-wrapped form can reproduce the hash) →
 * {@code {collisionTag}:__ropstmppart__:taskId:channel}, where collisionTag is the first
 * brace-free ASCII candidate whose CRC16 lands on the key's slot (deterministic; ~16k tries on a
 * balanced 14-bit space).
 * </ol>
 *
 * Keys sharing a tag map to the same temp name; splits are serial per sync channel and every begin
 * UNLINKs the residue, so names cannot overlap live.
 */
final class ClusterSplitChannel implements SplitTargetChannel {
    private final ClusterTargetRouter router;

    ClusterSplitChannel(ClusterTargetRouter router) {
        this.router = router;
    }

    @Override
    public void apply(byte[] slotKey, List<byte[][]> commands, TargetFence fence, LeaseGuard leaseGuard)
            throws IOException {
        router.applySplitBatch(slotKey, commands, fence, leaseGuard);
    }

    @Override
    public long llen(byte[] key) throws IOException {
        return router.splitLlen(key);
    }

    @Override
    public long exists(byte[] key) throws IOException {
        return router.splitExists(key);
    }

    @Override
    public long unlink(byte[] key) throws IOException {
        return router.splitUnlink(key);
    }

    @Override
    public long protoMaxBulkLen() throws IOException {
        return router.splitProtoMaxBulkLen();
    }

    @Override
    public void reopen(byte[] slotKey) {
        try {
            router.reopenSplit(slotKey);
        } catch (IOException refreshFailure) {
            // Topology refresh is best effort: the next attempt fails and retries again.
        }
    }

    @Override
    public byte[] tempKey(long taskId, String channel, byte[] key) {
        return clusterTempKey(taskId, channel, key);
    }

    @Override
    public void close() {
        // The router is owned by the sync channel and closed with it.
    }

    /** Slot-safe deterministic Cluster temp key (§6.1); see the class comment for the case split. */
    static byte[] clusterTempKey(long taskId, String channel, byte[] key) {
        byte[] hashed = effectiveTag(key);
        if (hashed == null && indexOf(key, 0, key.length, (byte) '}') < 0)
            hashed = key; // brace-wrapping the whole key reproduces its whole-key hash
        if (hashed == null)
            hashed = ascii(collisionTag(RedisSlot.of(key)));
        String suffix = ":__ropstmppart__:" + taskId + ":" + TargetCommandSession.safeChannel(channel);
        byte[] name = new byte[1 + hashed.length + 1 + suffix.length()];
        name[0] = '{';
        System.arraycopy(hashed, 0, name, 1, hashed.length);
        name[1 + hashed.length] = '}';
        System.arraycopy(suffix.getBytes(StandardCharsets.US_ASCII), 0, name, 2 + hashed.length,
                suffix.length());
        return name;
    }

    /**
     * The non-empty text between the first {@code '{'} and the next {@code '}'} — exactly the
     * substring Redis hashes — or null when the key has no valid tag (whole-key hash).
     */
    private static byte[] effectiveTag(byte[] key) {
        int open = indexOf(key, 0, key.length, (byte) '{');
        if (open < 0)
            return null;
        int close = indexOf(key, open + 1, key.length, (byte) '}');
        if (close <= open + 1)
            return null; // no closing brace, or empty tag: Redis hashes the whole key
        byte[] tag = new byte[close - open - 1];
        System.arraycopy(key, open + 1, tag, 0, tag.length);
        return tag;
    }

    /** First brace-free ASCII tag hashing to {@code slot}; deterministic smallest search index. */
    private static String collisionTag(int slot) {
        for (long attempt = 0; attempt < 2_000_000L; attempt++) {
            String candidate = "rsopstmppart" + attempt;
            if (RedisSlot.of(candidate) == slot)
                return candidate;
        }
        throw new IllegalStateException("cannot derive a slot-safe split temp key tag");
    }

    private static int indexOf(byte[] haystack, int from, int to, byte needle) {
        for (int i = from; i < to; i++)
            if (haystack[i] == needle)
                return i;
        return -1;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
