package io.github.redisops.sync.worker.domain;

import io.github.redisops.sync.contract.SyncContractStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Worker-owned copy of the persisted task transition contract. */
public final class WorkerSyncTransitionPolicy {
    private static final Map<SyncContractStatus, Set<SyncContractStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(SyncContractStatus.CREATED,
                    EnumSet.of(SyncContractStatus.CHECKING, SyncContractStatus.CANCELLED)),
            Map.entry(SyncContractStatus.CHECKING,
                    EnumSet.of(SyncContractStatus.READY, SyncContractStatus.FAILED, SyncContractStatus.CANCELLED)),
            Map.entry(SyncContractStatus.READY, EnumSet.of(SyncContractStatus.STARTING, SyncContractStatus.CANCELLED)),
            Map.entry(SyncContractStatus.STARTING,
                    EnumSet.of(SyncContractStatus.FULL_SYNCING, SyncContractStatus.INCR_SYNCING,
                            SyncContractStatus.RESUMING, SyncContractStatus.BLOCKED, SyncContractStatus.FAILED,
                            SyncContractStatus.CANCELLED)),
            Map.entry(SyncContractStatus.FULL_SYNCING,
                    EnumSet.of(SyncContractStatus.INCR_SYNCING, SyncContractStatus.RESUMING, SyncContractStatus.PAUSING,
                            SyncContractStatus.BLOCKED, SyncContractStatus.FAILED, SyncContractStatus.CANCELLED)),
            Map.entry(SyncContractStatus.INCR_SYNCING,
                    EnumSet.of(SyncContractStatus.CAUGHT_UP, SyncContractStatus.RESUMING, SyncContractStatus.PAUSING,
                            SyncContractStatus.STOPPING, SyncContractStatus.BLOCKED, SyncContractStatus.FAILED,
                            SyncContractStatus.CANCELLED)),
            Map.entry(SyncContractStatus.CAUGHT_UP,
                    EnumSet.of(SyncContractStatus.INCR_SYNCING, SyncContractStatus.RESUMING, SyncContractStatus.PAUSING,
                            SyncContractStatus.STOPPING, SyncContractStatus.BLOCKED, SyncContractStatus.FAILED,
                            SyncContractStatus.CANCELLED)),
            Map.entry(SyncContractStatus.PAUSING,
                    EnumSet.of(SyncContractStatus.PAUSED, SyncContractStatus.BLOCKED, SyncContractStatus.FAILED)),
            Map.entry(SyncContractStatus.PAUSED, EnumSet.of(SyncContractStatus.RESUMING, SyncContractStatus.CANCELLED)),
            Map.entry(SyncContractStatus.RESUMING,
                    EnumSet.of(SyncContractStatus.FULL_SYNCING, SyncContractStatus.INCR_SYNCING,
                            SyncContractStatus.CAUGHT_UP, SyncContractStatus.BLOCKED, SyncContractStatus.FAILED)),
            Map.entry(SyncContractStatus.STOPPING,
                    EnumSet.of(SyncContractStatus.FINISHED, SyncContractStatus.BLOCKED, SyncContractStatus.FAILED)),
            Map.entry(SyncContractStatus.BLOCKED,
                    EnumSet.of(SyncContractStatus.RESUMING, SyncContractStatus.CHECKING, SyncContractStatus.CANCELLED)),
            Map.entry(SyncContractStatus.FAILED, EnumSet.of(SyncContractStatus.CHECKING, SyncContractStatus.CANCELLED)),
            Map.entry(SyncContractStatus.FINISHED, EnumSet.noneOf(SyncContractStatus.class)),
            Map.entry(SyncContractStatus.CANCELLED, EnumSet.noneOf(SyncContractStatus.class)));

    private WorkerSyncTransitionPolicy() {
    }

    public static boolean canTransition(SyncContractStatus from, SyncContractStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
