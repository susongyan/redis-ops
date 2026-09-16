package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.CommandPlan;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Runs the same real-server failure matrix on the node owning slot 0 of a disposable Cluster. */
class ClusterTargetBatchRedisTest extends TargetBatchRedisTest {
    @Override
    String endpointVariable() {
        return "SYNC_BATCH_TEST_CLUSTER_SLOT0";
    }

    @Override
    TargetCommandSession session() throws Exception {
        var profile = new WorkerRedisConnectionProfile(1, WorkerClusterMode.CLUSTER,
                List.of(endpoint), null, null, "NONE", null);
        return TargetCommandSession.clusterSlot(profile, RedisEndpoint.parse(endpoint), taskId,
                Duration.ofSeconds(2), "fixture", 0);
    }

    @Override
    String key(String suffix) {
        return "batch-fixture:{" + ClusterSlotKeyspace.tag(0) + "}:" + taskId + ":" + suffix;
    }

    @Override
    String checkpointKey() {
        return new String(ClusterSlotKeyspace.checkpoint(taskId, "fixture", 0), StandardCharsets.US_ASCII);
    }

    @Override
    CommandPlan.PlannedCommand planned(String... args) {
        return new CommandPlan.PlannedCommand(0, super.planned(args).arguments());
    }
}
