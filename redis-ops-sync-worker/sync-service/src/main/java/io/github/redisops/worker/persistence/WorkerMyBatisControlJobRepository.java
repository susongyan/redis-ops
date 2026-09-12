package io.github.redisops.worker.persistence;

import io.github.redisops.worker.runtime.WorkerControlJob;
import io.github.redisops.worker.runtime.WorkerControlJobPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

@Repository
public class WorkerMyBatisControlJobRepository implements WorkerControlJobPort {
    private final WorkerControlJobMapper mapper;
    public WorkerMyBatisControlJobRepository(WorkerControlJobMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    @Transactional
    public Optional<WorkerControlJob> claim(String type, String owner, Duration duration) {
        return mapper.claim(type, owner, duration.toSeconds()) == 1
                ? Optional.ofNullable(mapper.findClaimed(owner))
                : Optional.empty();
    }
    @Override
    @Transactional
    public Optional<WorkerControlJob> claimForRuntime(String type, String owner, String runtimeOwner, Duration duration,
            boolean allowExpiredRuntime) {
        return mapper.claimRouted(type, owner, runtimeOwner, duration.toSeconds(), allowExpiredRuntime) == 1
                ? Optional.ofNullable(mapper.findClaimed(owner))
                : Optional.empty();
    }
    @Override
    public void complete(long id, String owner) {
        if (mapper.complete(id, owner) != 1)
            throw new IllegalStateException("job lease lost: " + id);
    }
    @Override
    public boolean retryOrFail(long id, String owner, String error) {
        if (mapper.retryOrFail(id, owner, error) != 1)
            throw new IllegalStateException("job lease lost: " + id);
        return "FAILED".equals(mapper.status(id));
    }
}
