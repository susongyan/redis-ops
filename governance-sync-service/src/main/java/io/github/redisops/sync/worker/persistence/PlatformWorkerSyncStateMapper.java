package io.github.redisops.sync.worker.persistence;

import io.github.redisops.domain.sync.SyncRuntime;
import io.github.redisops.domain.sync.SyncTask;
import io.github.redisops.domain.sync.SyncTaskStatus;
import io.github.redisops.sync.contract.SyncContractStatus;
import io.github.redisops.sync.worker.domain.WorkerSyncRuntime;
import io.github.redisops.sync.worker.domain.WorkerSyncTask;

/** Temporary mapping boundary while Worker persistence still reads the shared schema through Platform adapters. */
final class PlatformWorkerSyncStateMapper {
    private PlatformWorkerSyncStateMapper() {
    }

    static WorkerSyncTask toWorkerTask(SyncTask task) {
        return new WorkerSyncTask(task.id(), task.taskNo(), task.relationId(), task.sourceClusterId(),
                task.targetClusterId(), task.purpose().name(), task.syncMode().name(),
                SyncContractStatus.valueOf(task.status().name()), task.toolType(), task.sourceDb(), task.targetDb(),
                task.includePatternsJson(), task.excludePatternsJson(), task.commandPolicyJson(), task.rateLimitOps(),
                task.bandwidthLimitBytesPerSecond(), task.spoolLimitBytes(), task.fullApplyConcurrency(),
                task.fullApplyPipelineSize(), task.desiredAction(), task.writeFenced(), task.writeFenceNote(),
                task.blockedReason(), task.fullSyncEpoch(), task.lastRpoSeconds(), task.lastError(), task.version(),
                task.createdAt(), task.updatedAt(), task.finishedAt());
    }

    static WorkerSyncRuntime toWorkerRuntime(SyncRuntime runtime) {
        return new WorkerSyncRuntime(runtime.taskId(), runtime.runtimeId(), runtime.leaseOwner(), runtime.leaseUntil(),
                runtime.fencingGeneration(), runtime.phase(), runtime.heartbeatAt(), runtime.spoolBytes(),
                runtime.targetFenceGeneration(), runtime.fencePublishedAt(), runtime.takeoverCount(),
                runtime.recoveryAction(), runtime.lastError(), runtime.startedAt(), runtime.updatedAt());
    }

    static SyncTaskStatus toPlatformStatus(SyncContractStatus status) {
        return SyncTaskStatus.valueOf(status.name());
    }
}
