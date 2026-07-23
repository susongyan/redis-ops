package io.github.redisops.domain.sync;
import java.time.Instant;
public record Switchover(Long id,long relationId,long oldPrimaryClusterId,long oldStandbyClusterId,
                         long stoppedTaskId,Long reverseTaskId,SwitchoverStatus status,String operator,
                         String lastError,long version,Instant createdAt,Instant updatedAt,Instant confirmedAt) { }
