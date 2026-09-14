package io.github.redisops.platform.domain.distribution;

import java.util.List;

public record DistributionSpec(long clusterId, int database, List<DistributionRule> rules,
        DistributionCounter.Mode mode, int capacity, long maxObservations, int durationSeconds, int keysPerSecond) {
    public DistributionSpec {
        new DistributionClassifier(rules);
        rules = List.copyOf(rules);
        if (clusterId < 1 || database < 0 || database > 15 || mode == null || capacity < 1 || capacity > 1000
                || maxObservations < 0 || maxObservations > 1_000_000_000_000L || durationSeconds < 1
                || durationSeconds > 21600
                || keysPerSecond < 1 || keysPerSecond > 1000)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_BUDGET");
        new DistributionCounter(mode, capacity, rules);
    }
}
