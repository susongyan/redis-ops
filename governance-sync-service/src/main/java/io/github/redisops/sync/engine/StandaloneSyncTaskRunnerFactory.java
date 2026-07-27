package io.github.redisops.sync.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.domain.asset.RedisConnectionProfileProvider;
import io.github.redisops.domain.sync.SyncRepository;
import io.github.redisops.domain.sync.SyncTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

@Component
public class StandaloneSyncTaskRunnerFactory implements SyncTaskRunnerFactory {
    private final RedisConnectionProfileProvider profiles;
    private final SyncRepository sync;
    private final SyncRunnerStateReporter reporter;
    private final SpoolKeyProvider spoolKeys;
    private final RedisDataEndpointResolver endpoints;
    private final ObjectMapper json;
    private final Path dataDirectory;
    private final long segmentBytes;
    private final Duration connectTimeout;
    private final int fullApplyConcurrency;
    private final int fullApplyQueueCapacity;
    private final int fullApplyPipelineSize;
    private final long fullApplyTransactionMaxBytes;
    private final Duration leaseSafetyMargin;
    private final Duration metricInterval;

    public StandaloneSyncTaskRunnerFactory(RedisConnectionProfileProvider profiles, SyncRepository sync,
            SyncRunnerStateReporter reporter, SpoolKeyProvider spoolKeys, RedisDataEndpointResolver endpoints,
            ObjectMapper json,
            @Value("${sync.engine.data-dir:./data/sync}") Path dataDirectory,
            @Value("${sync.engine.segment-bytes:268435456}") long segmentBytes,
            @Value("${sync.engine.connect-timeout-ms:10000}") long connectTimeoutMillis,
            @Value("${sync.engine.full-apply-concurrency:4}") int fullApplyConcurrency,
            @Value("${sync.engine.full-apply-queue-capacity:2000}") int fullApplyQueueCapacity,
            @Value("${sync.engine.full-apply-pipeline-size:100}") int fullApplyPipelineSize,
            @Value("${sync.engine.full-apply-transaction-max-bytes:4194304}") long fullApplyTransactionMaxBytes,
            @Value("${sync.engine.lease-safety-margin-ms:2000}") long leaseSafetyMarginMillis,
            @Value("${sync.engine.metric-interval-ms:1000}") long metricIntervalMillis) {
        if (fullApplyConcurrency < 1 || fullApplyConcurrency > 64)
            throw new IllegalArgumentException("full apply concurrency must be between 1 and 64");
        if (fullApplyQueueCapacity < fullApplyConcurrency)
            throw new IllegalArgumentException("full apply queue capacity must be at least the concurrency");
        if (fullApplyPipelineSize < 1 || fullApplyPipelineSize > 10_000)
            throw new IllegalArgumentException("full apply pipeline size must be between 1 and 10000");
        if (fullApplyTransactionMaxBytes < 1024)
            throw new IllegalArgumentException("full apply transaction max bytes must be at least 1024");
        if (leaseSafetyMarginMillis < 0)
            throw new IllegalArgumentException("lease safety margin cannot be negative");
        if (metricIntervalMillis < 1)
            throw new IllegalArgumentException("metric interval must be positive");
        this.profiles = profiles;
        this.sync = sync;
        this.reporter = reporter;
        this.spoolKeys = spoolKeys;
        this.endpoints = endpoints;
        this.json = json;
        this.dataDirectory = dataDirectory;
        this.segmentBytes = segmentBytes;
        this.connectTimeout = Duration.ofMillis(connectTimeoutMillis);
        this.fullApplyConcurrency = fullApplyConcurrency;
        this.fullApplyQueueCapacity = fullApplyQueueCapacity;
        this.fullApplyPipelineSize = fullApplyPipelineSize;
        this.fullApplyTransactionMaxBytes = fullApplyTransactionMaxBytes;
        this.leaseSafetyMargin = Duration.ofMillis(leaseSafetyMarginMillis);
        this.metricInterval = Duration.ofMillis(metricIntervalMillis);
    }

    @Override
    public SyncTaskRunner create(SyncTask task, boolean recovery) {
        return new StandaloneSyncTaskRunner(task, recovery, profiles, sync, reporter, spoolKeys, endpoints, json,
                dataDirectory, segmentBytes, connectTimeout, fullApplyConcurrency,
                fullApplyQueueCapacity, fullApplyPipelineSize, fullApplyTransactionMaxBytes, leaseSafetyMargin,
                metricInterval);
    }
}
