package io.github.redisops.domain.idempotency;

import java.util.Optional;

public interface IdempotencyRepository {
    boolean tryStart(String operator, String key, String operation, String requestDigest);
    Optional<IdempotencyRecord> find(String operator, String key);
    void complete(String operator, String key, String resourceId);
    void fail(String operator, String key);
}
