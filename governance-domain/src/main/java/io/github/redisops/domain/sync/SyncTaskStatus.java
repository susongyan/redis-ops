package io.github.redisops.domain.sync;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum SyncTaskStatus {
    CREATED, CHECKING, READY, FULL_SYNCING, INCR_SYNCING, CAUGHT_UP, FINISHED,
    FAILED, PAUSED, CANCELLED;

    private static final Map<SyncTaskStatus, Set<SyncTaskStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(CHECKING, CANCELLED)),
            Map.entry(CHECKING, EnumSet.of(READY, FAILED, CANCELLED)),
            Map.entry(READY, EnumSet.of(FULL_SYNCING, CANCELLED)),
            Map.entry(FULL_SYNCING, EnumSet.of(INCR_SYNCING, PAUSED, FAILED, CANCELLED)),
            Map.entry(INCR_SYNCING, EnumSet.of(CAUGHT_UP, PAUSED, FAILED, CANCELLED)),
            Map.entry(CAUGHT_UP, EnumSet.of(FINISHED, PAUSED, FAILED, CANCELLED)),
            Map.entry(PAUSED, EnumSet.of(FULL_SYNCING, INCR_SYNCING, CAUGHT_UP, CANCELLED)),
            Map.entry(FAILED, EnumSet.of(CHECKING, CANCELLED)),
            Map.entry(FINISHED, EnumSet.noneOf(SyncTaskStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(SyncTaskStatus.class)));

    public boolean canTransitionTo(SyncTaskStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean terminal() { return this==FINISHED||this==CANCELLED; }
}
