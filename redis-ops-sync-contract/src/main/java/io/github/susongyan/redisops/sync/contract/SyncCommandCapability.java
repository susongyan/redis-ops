package io.github.susongyan.redisops.sync.contract;

public record SyncCommandCapability(String command, String category, String reason, boolean configurable,
        boolean currentlyBlocked) {
}
