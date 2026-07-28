package io.github.redisops.domain.sync;

public record SyncCommandCapability(
        String command, String category, String reason, boolean configurable, boolean currentlyBlocked) {
}
