package io.github.redisops.platform.infrastructure.distribution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment may tighten, but cannot enlarge, these allocation and network limits. */
@Component
public record DistributionReceiveLimits(int responseBytes, int keyBytes, int elements, int shards,
        int scanCount, int connectMillis, int commandMillis) {
    public DistributionReceiveLimits(
            @Value("${distribution.max-response-bytes:1048576}") int responseBytes,
            @Value("${distribution.max-key-bytes:4096}") int keyBytes,
            @Value("${distribution.max-response-elements:5000}") int elements,
            @Value("${distribution.max-shards:256}") int shards,
            @Value("${distribution.scan-count:200}") int scanCount,
            @Value("${distribution.connect-timeout-ms:2000}") int connectMillis,
            @Value("${distribution.command-timeout-ms:3000}") int commandMillis) {
        if (responseBytes < 128 || responseBytes > 1048576 || keyBytes < 1 || keyBytes > 4096
                || elements < 1 || elements > 5000 || shards < 1 || shards > 256 || scanCount < 1 || scanCount > 200
                || connectMillis < 1 || connectMillis > 2000 || commandMillis < 1 || commandMillis > 3000)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_RECEIVE_LIMITS");
        this.responseBytes = responseBytes;
        this.keyBytes = keyBytes;
        this.elements = elements;
        this.shards = shards;
        this.scanCount = scanCount;
        this.connectMillis = connectMillis;
        this.commandMillis = commandMillis;
    }
    public static DistributionReceiveLimits defaults() {
        return new DistributionReceiveLimits(1048576, 4096, 5000, 256, 200, 2000, 3000);
    }
}
