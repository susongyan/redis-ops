package io.github.redisops.sync.worker.domain;

import io.github.redisops.sync.contract.SyncContractStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerSyncTransitionPolicyTest {
    @Test
    void preservesWorkerEngineTransitions() {
        assertThat(WorkerSyncTransitionPolicy.canTransition(SyncContractStatus.STARTING,
                SyncContractStatus.FULL_SYNCING)).isTrue();
        assertThat(WorkerSyncTransitionPolicy.canTransition(SyncContractStatus.FULL_SYNCING,
                SyncContractStatus.CAUGHT_UP)).isFalse();
        assertThat(WorkerSyncTransitionPolicy.canTransition(SyncContractStatus.CAUGHT_UP,
                SyncContractStatus.STOPPING)).isTrue();
        assertThat(WorkerSyncTransitionPolicy.canTransition(SyncContractStatus.FINISHED,
                SyncContractStatus.CHECKING)).isFalse();
    }
}
