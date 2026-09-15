package io.github.susongyan.redisops.worker.domain;

import java.time.Instant;

/** Fail-closed runtime and target-fence observation from the Worker. */
public record WorkerRuntimeObservation(long taskId, String owner, String phase, Long targetFenceGeneration,
        Instant fencePublishedAt, String recoveryAction, String error) {
}
