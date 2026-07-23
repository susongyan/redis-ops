package io.github.redisops.domain.sync;
import java.time.Instant;
public record SyncTask(Long id,String taskNo,Long relationId,long sourceClusterId,long targetClusterId,
                       SyncPurpose purpose,SyncMode syncMode,SyncTaskStatus status,String toolType,
                       Long lastRpoSeconds,String lastError,long version,Instant createdAt,Instant updatedAt,Instant finishedAt) { }
