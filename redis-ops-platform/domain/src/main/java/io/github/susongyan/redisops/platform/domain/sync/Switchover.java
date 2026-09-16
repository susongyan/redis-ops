package io.github.susongyan.redisops.platform.domain.sync;
import java.time.Instant;
public record Switchover(Long id, long relationId, long oldPrimaryClusterId, long oldStandbyClusterId,
        long stoppedTaskId, Long reverseTaskId, SwitchoverStatus status, String operator,
        boolean sourceWriteFenced, String sourceFenceNote, String lastError, long version,
        Instant createdAt, Instant updatedAt, Instant confirmedAt, String operatorSnapshot) {
    public Switchover(Long id, long relationId, long oldPrimaryClusterId, long oldStandbyClusterId,
            long stoppedTaskId, Long reverseTaskId, SwitchoverStatus status, String operator,
            boolean sourceWriteFenced, String sourceFenceNote, String lastError, long version,
            Instant createdAt, Instant updatedAt, Instant confirmedAt) {
        this(id, relationId, oldPrimaryClusterId, oldStandbyClusterId, stoppedTaskId, reverseTaskId, status, operator,
                sourceWriteFenced, sourceFenceNote, lastError, version, createdAt, updatedAt, confirmedAt, null);
    }
}
