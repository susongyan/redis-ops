package io.github.redisops.domain.sync;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum SyncTaskStatus {
    CREATED, CHECKING, READY, STARTING, FULL_SYNCING, INCR_SYNCING, CAUGHT_UP, PAUSING, PAUSED, RESUMING, STOPPING, BLOCKED, FAILED, FINISHED, CANCELLED;

    private static final Map<SyncTaskStatus, Set<SyncTaskStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(CHECKING, CANCELLED)),
            Map.entry(CHECKING, EnumSet.of(READY, FAILED, CANCELLED)),
            Map.entry(READY, EnumSet.of(STARTING, CANCELLED)),
            Map.entry(STARTING, EnumSet.of(FULL_SYNCING, INCR_SYNCING, RESUMING, BLOCKED, FAILED, CANCELLED)),
            Map.entry(FULL_SYNCING, EnumSet.of(INCR_SYNCING, RESUMING, PAUSING, BLOCKED, FAILED, CANCELLED)),
            Map.entry(INCR_SYNCING, EnumSet.of(CAUGHT_UP, RESUMING, PAUSING, STOPPING, BLOCKED, FAILED, CANCELLED)),
            Map.entry(CAUGHT_UP, EnumSet.of(INCR_SYNCING, RESUMING, PAUSING, STOPPING, BLOCKED, FAILED, CANCELLED)),
            Map.entry(PAUSING, EnumSet.of(PAUSED, BLOCKED, FAILED)),
            Map.entry(PAUSED, EnumSet.of(RESUMING, CANCELLED)),
            Map.entry(RESUMING, EnumSet.of(FULL_SYNCING, INCR_SYNCING, CAUGHT_UP, BLOCKED, FAILED)),
            Map.entry(STOPPING, EnumSet.of(FINISHED, BLOCKED, FAILED)),
            Map.entry(BLOCKED, EnumSet.of(RESUMING, CHECKING, CANCELLED)),
            Map.entry(FAILED, EnumSet.of(CHECKING, CANCELLED)),
            Map.entry(FINISHED, EnumSet.noneOf(SyncTaskStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(SyncTaskStatus.class)));

    public boolean canTransitionTo(SyncTaskStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean terminal() {
        return this == FINISHED || this == CANCELLED;
    }
}
