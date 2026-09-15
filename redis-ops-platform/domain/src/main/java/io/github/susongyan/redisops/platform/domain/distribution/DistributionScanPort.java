package io.github.susongyan.redisops.platform.domain.distribution;

import java.util.List;

public interface DistributionScanPort {
    record Shard(String id, String endpoint) {
    }
    record Topology(String fingerprint, List<Shard> shards) {
        public Topology {
            shards = List.copyOf(shards);
            if (shards.isEmpty() || shards.size() > 256)
                throw new IllegalArgumentException("DISTRIBUTION_SHARD_LIMIT");
        }
    }
    /** Null entries represent discarded oversized keys. The complete page has already passed all limits. */
    record Page(String cursor, List<byte[]> keys) {
    }
    Topology topology(long clusterId, int database);
    Page scan(long clusterId, int database, Shard shard, String cursor, int count);
    default Topology topology(long clusterId, int database, long deadlineNanos) {
        return topology(clusterId, database);
    }
    default Page scan(long clusterId, int database, Shard shard, String cursor, int count, long deadlineNanos) {
        return scan(clusterId, database, shard, cursor, count);
    }
}
