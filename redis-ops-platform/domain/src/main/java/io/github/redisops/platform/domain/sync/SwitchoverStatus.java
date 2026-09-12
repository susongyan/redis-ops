package io.github.redisops.platform.domain.sync;
public enum SwitchoverStatus {
    WAITING_SOURCE_FENCE, DRAINING, WAITING_EXTERNAL_SWITCH, CONFIRMED, CANCELLED, FAILED
}
