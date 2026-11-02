package io.github.susongyan.redisops.platform.domain.scan;

import java.util.ArrayList;
import java.util.List;

/** Bounded, node-pinned advisory reads. */
public interface ReadOnlyScanPort {
    record Shard(String id, String endpoint, String role, String runId, String nodeId) {
        public Shard {
            role = role == null ? "master" : role;
            nodeId = nodeId == null ? id : nodeId;
        }
        public Shard(String id, String endpoint) {
            this(id, endpoint, "master", null, id);
        }
    }
    record Topology(String fingerprint, List<Shard> shards) {
        public Topology {
            shards = List.copyOf(shards);
            if (shards.isEmpty() || shards.size() > 256)
                throw new IllegalArgumentException("DISTRIBUTION_SHARD_LIMIT");
        }
        public String checkpointId(Shard shard) {
            try {
                String identity = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest((fingerprint + "|" + shard.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                String node = shard.endpoint().length() <= 50 ? shard.endpoint() : shard.nodeId();
                return shard.role() + "@" + node + "#" + identity;
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
    record Page(String cursor, List<byte[]> keys) {
    }
    record Metadata(String type, long size, long ttlSeconds) {
    }
    /** Reusable per-shard inspection session: one pinned connection for many metadata reads. */
    interface Session extends AutoCloseable {
        Metadata metadata(byte[] key);
        /** Pipelined counterpart of {@link #metadata}; the result is positionally aligned with {@code keys}. */
        default List<Metadata> metadataBatch(List<byte[]> keys) {
            List<Metadata> values = new ArrayList<>(keys.size());
            for (byte[] key : keys)
                values.add(metadata(key));
            return values;
        }
        /** Pipelined TTL-only read; positionally aligned with {@code keys}, -2 when the key is absent. */
        default List<Long> ttlBatch(List<byte[]> keys) {
            List<Long> values = new ArrayList<>(keys.size());
            for (Metadata metadata : metadataBatch(keys))
                values.add(metadata.ttlSeconds());
            return values;
        }
        /** Cursor scan over the shard this session is pinned to. */
        default Page scan(String cursor, int count) {
            throw new UnsupportedOperationException("SCAN_SESSION_UNAVAILABLE");
        }
        @Override
        void close();
    }
    Topology topology(long clusterId, int database);
    Page scan(long clusterId, int database, Shard shard, String cursor, int count);
    default Session open(long clusterId, int database, Shard shard) {
        throw new UnsupportedOperationException("SCAN_SESSION_UNAVAILABLE");
    }
    default Topology topology(long clusterId, int database, long deadlineNanos) {
        return topology(clusterId, database);
    }
    default Page scan(long clusterId, int database, Shard shard, String cursor, int count, long deadlineNanos) {
        return scan(clusterId, database, shard, cursor, count);
    }
    default Metadata metadata(long clusterId, int database, Shard shard, byte[] key) {
        throw new UnsupportedOperationException("SCAN_METADATA_UNAVAILABLE");
    }
    default long count(long clusterId, int database, Topology topology) {
        throw new UnsupportedOperationException("SCAN_COUNT_UNAVAILABLE");
    }
}
