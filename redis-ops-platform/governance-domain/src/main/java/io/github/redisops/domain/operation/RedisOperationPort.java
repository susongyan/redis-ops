package io.github.redisops.domain.operation;

import java.util.List;

public interface RedisOperationPort {
    OperationResult execute(long clusterId, int databaseNo, String command, List<String> arguments);

    record OperationResult(boolean success, String type, String value, long valueLength, long ttlSeconds,
            String errorCode) {
    }
}
