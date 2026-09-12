package io.github.redisops.domain.job;

import java.time.Duration;
import java.util.Optional;

public interface JobRepository {
    AsyncJob enqueue(String jobType, long bizId, String payload, String idempotencyKey);
    Optional<AsyncJob> findById(long id);
    Optional<AsyncJob> claimNext(String jobType, String leaseOwner, Duration leaseDuration);
    Optional<AsyncJob> claimNextRouted(String jobType, String leaseOwner, String runtimeOwner,
            Duration leaseDuration, boolean allowExpiredRuntime);
    void complete(long id, String leaseOwner);
    boolean retryOrFail(long id, String leaseOwner, String error);
}
