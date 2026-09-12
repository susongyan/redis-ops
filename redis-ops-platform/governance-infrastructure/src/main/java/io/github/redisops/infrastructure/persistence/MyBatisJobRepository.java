package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.job.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.Optional;

@Repository
public class MyBatisJobRepository implements JobRepository {
    private final JobMapper mapper;
    public MyBatisJobRepository(JobMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    public AsyncJob enqueue(String type, long bizId, String payload, String key) {
        JobMapper.JobRow row = new JobMapper.JobRow();
        row.jobType = type;
        row.bizId = bizId;
        row.payload = payload;
        row.idempotencyKey = key;
        try {
            mapper.insert(row);
            return mapper.findById(row.id);
        } catch (DuplicateKeyException e) {
            return mapper.findByKey(key);
        }
    }
    @Override
    public Optional<AsyncJob> findById(long id) {
        return Optional.ofNullable(mapper.findById(id));
    }
    @Override
    @Transactional
    public Optional<AsyncJob> claimNext(String type, String owner, Duration duration) {
        return mapper.claim(type, owner, duration.toSeconds()) == 1
                ? Optional.ofNullable(mapper.findClaimed(owner))
                : Optional.empty();
    }
    @Override
    @Transactional
    public Optional<AsyncJob> claimNextRouted(String type, String owner, String runtimeOwner,
            Duration duration, boolean allowExpiredRuntime) {
        return mapper.claimRouted(type, owner, runtimeOwner, duration.toSeconds(), allowExpiredRuntime) == 1
                ? Optional.ofNullable(mapper.findClaimed(owner))
                : Optional.empty();
    }
    @Override
    public void complete(long id, String owner) {
        mapper.complete(id, owner);
    }
    @Override
    public boolean retryOrFail(long id, String owner, String error) {
        mapper.retryOrFail(id, owner, error);
        return "FAILED".equals(mapper.findById(id).status());
    }
}
