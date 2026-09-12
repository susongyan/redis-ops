package io.github.redisops.worker.runtime;

public final class SyncBlockedException extends RuntimeException {
    private final String reason;

    public SyncBlockedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
