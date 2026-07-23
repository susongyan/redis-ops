package io.github.redisops.domain.job;

import java.time.Instant;

public record AsyncJob(Long id, String jobType, long bizId, String payload, String status,
                       String idempotencyKey, String leaseOwner, Instant leaseUntil,
                       int attempts, int maxAttempts, String lastError) { }
