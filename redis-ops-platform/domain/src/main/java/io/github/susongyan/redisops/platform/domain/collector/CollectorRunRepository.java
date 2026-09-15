package io.github.susongyan.redisops.platform.domain.collector;

public interface CollectorRunRepository {
    long start(long clusterId, String collectionType);
    void finish(long id, String status, String summaryJson, String errorCode, String errorMessage);
}
