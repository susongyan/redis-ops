package io.github.redisops.platform.domain.validation;

public enum ValidationTaskStatus {
    CREATED, CHECKING, READY, RUNNING, PASSED, FAILED, INCONCLUSIVE, CANCELLED
}
