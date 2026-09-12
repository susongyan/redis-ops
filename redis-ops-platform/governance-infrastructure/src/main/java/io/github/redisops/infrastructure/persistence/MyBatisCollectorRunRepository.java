package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.collector.CollectorRunRepository;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCollectorRunRepository implements CollectorRunRepository {
    private final CollectorRunMapper mapper;

    public MyBatisCollectorRunRepository(CollectorRunMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long start(long clusterId, String collectionType) {
        var row = new CollectorRunMapper.Row();
        row.clusterId = clusterId;
        row.type = collectionType;
        row.startedAt = Instant.now();
        mapper.start(row);
        return row.id;
    }

    @Override
    public void finish(long id, String status, String summaryJson, String errorCode, String errorMessage) {
        mapper.finish(id, status, summaryJson, errorCode, errorMessage);
    }
}
