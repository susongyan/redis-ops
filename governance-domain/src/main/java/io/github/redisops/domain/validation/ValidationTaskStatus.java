package io.github.redisops.domain.validation;

public enum ValidationTaskStatus {
    CREATED, CHECKING, READY, RUNNING, PASSED, FAILED, INCONCLUSIVE, CANCELLED
}
