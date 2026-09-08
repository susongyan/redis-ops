package io.github.redisops.sync.engine;

import io.github.redisops.domain.job.AsyncJob;
import io.github.redisops.domain.job.JobRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Compatibility adapter to be replaced by the Worker MyBatis persistence module. */
@Component
public final class PlatformWorkerControlJobAdapter implements WorkerControlJobPort {
    private final JobRepository delegate;

    public PlatformWorkerControlJobAdapter(JobRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<WorkerControlJob> claim(String type, String leaseOwner, Duration leaseDuration) {
        return delegate.claimNext(type, leaseOwner, leaseDuration).map(this::toWorkerJob);
    }

    @Override
    public Optional<WorkerControlJob> claimForRuntime(String type, String leaseOwner, String runtimeOwner,
            Duration leaseDuration, boolean allowExpiredRuntime) {
        return delegate.claimNextRouted(type, leaseOwner, runtimeOwner, leaseDuration, allowExpiredRuntime)
                .map(this::toWorkerJob);
    }

    @Override
    public void complete(long jobId, String leaseOwner) {
        delegate.complete(jobId, leaseOwner);
    }

    @Override
    public boolean retryOrFail(long jobId, String leaseOwner, String error) {
        return delegate.retryOrFail(jobId, leaseOwner, error);
    }

    private WorkerControlJob toWorkerJob(AsyncJob job) {
        return new WorkerControlJob(job.id(), job.jobType(), job.bizId(), job.leaseOwner());
    }
}
