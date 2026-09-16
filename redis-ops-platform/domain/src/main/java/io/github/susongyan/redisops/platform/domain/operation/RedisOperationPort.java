package io.github.susongyan.redisops.platform.domain.operation;

import java.util.List;

public interface RedisOperationPort {
    OperationResult execute(long clusterId, int databaseNo, String command, List<String> arguments);
    default OperationResult execute(long clusterId, int databaseNo, String command, List<String> arguments,
            int keyPosition) {
        return execute(clusterId, databaseNo, command, arguments);
    }

    record OperationResult(boolean success, String type, String value, long valueLength, long ttlSeconds,
            String errorCode) {
    }
}
