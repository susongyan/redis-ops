package io.github.susongyan.redisops.platform.application.sync;

import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.domain.sync.SyncRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Wires worker big-key split skip events (design doc §5.3 trio, platform side) into the existing
 * LARGE_KEY_FOUND alert rule: the worker already logs, counts and appends a SPLIT_KEY_SKIPPED
 * task event, this scan makes those skips visible on the alert board as well.
 *
 * Dedup is per open episode: while an OPEN/ACKNOWLEDGED event exists for the task no new
 * notification is enqueued, so the poll cadence never spams the channel; after an operator
 * resolves the event, the next skip inside the scan window re-opens it.
 */
@Service
public class SyncSkipAlertService {
    /** Same rule type as the risk scan's large-key findings; tasks alert as their own resource. */
    static final String RULE_TYPE = "LARGE_KEY_FOUND";
    static final String RESOURCE_TYPE = "SYNC_TASK";

    private final SyncRepository sync;
    private final AlertService alerts;
    private final long windowSeconds;

    public SyncSkipAlertService(SyncRepository sync, AlertService alerts,
            @Value("${sync.skip-alert.window-seconds:600}") long windowSeconds) {
        this.sync = sync;
        this.alerts = alerts;
        this.windowSeconds = windowSeconds;
    }

    /** Alerts every task with fresh skip events; returns how many alerts were raised this pass. */
    public int alertRecentSkips() {
        int raised = 0;
        for (Long taskId : sync.findSplitSkipTaskIds(windowSeconds)) {
            String resourceId = Long.toString(taskId);
            if (alerts.hasUnresolved(RULE_TYPE, RESOURCE_TYPE, resourceId))
                continue;
            long count = sync.countSplitSkips(taskId);
            if (count == 0)
                continue;
            String taskNo = sync.findTask(taskId).map(task -> task.taskNo()).orElse("task-" + taskId);
            alerts.trigger(RULE_TYPE, RESOURCE_TYPE, resourceId, (double) count,
                    "Full sync of " + taskNo + " skipped " + count
                            + " key(s) over the hard cap or of an unsupported type; "
                            + "the target is missing that data — see the task's skip list");
            raised++;
        }
        return raised;
    }
}
