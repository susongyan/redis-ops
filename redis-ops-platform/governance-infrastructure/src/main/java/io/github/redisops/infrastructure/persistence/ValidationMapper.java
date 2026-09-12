package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.validation.*;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ValidationMapper {
    String TASK_COLUMNS = "id,task_no,sync_task_id,source_cluster_id,target_cluster_id,source_db,target_db,strictness,"
            + "CAST(include_patterns_json AS CHAR) include_patterns_json,CAST(exclude_patterns_json AS CHAR) exclude_patterns_json,"
            + "sample_seed,sampling_mode,sample_limit,sample_percentage,ttl_tolerance_seconds,large_key_threshold_bytes,max_deep_compare_bytes,chunk_bytes,"
            + "max_elements_per_key,status,last_error,version,created_at,updated_at";
    String RUN_COLUMNS = "id,task_id,run_no,status,planned_keys,scanned_keys,compared_keys,difference_count,degraded_count,"
            + "unverifiable_count,inconclusive_count,started_at,finished_at,CAST(summary_json AS CHAR) summary_json";

    @Insert("""
            INSERT INTO validation_task(task_no,sync_task_id,source_cluster_id,target_cluster_id,source_db,target_db,strictness,
              include_patterns_json,exclude_patterns_json,sample_seed,sampling_mode,sample_limit,sample_percentage,ttl_tolerance_seconds,
              large_key_threshold_bytes,max_deep_compare_bytes,chunk_bytes,max_elements_per_key,status,last_error)
            VALUES(#{taskNo},#{syncTaskId},#{sourceClusterId},#{targetClusterId},#{sourceDb},#{targetDb},#{strictness},
              CAST(#{includePatternsJson} AS JSON),CAST(#{excludePatternsJson} AS JSON),#{sampleSeed},#{samplingMode},#{sampleLimit},#{samplePercentage},
              #{ttlToleranceSeconds},#{largeKeyThresholdBytes},#{maxDeepCompareBytes},#{chunkBytes},#{maxElementsPerKey},
              #{status},#{lastError})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertTask(TaskRow row);
    @Select("SELECT " + TASK_COLUMNS + " FROM validation_task WHERE id=#{id}")
    ValidationTask findTask(long id);
    @Select("SELECT " + TASK_COLUMNS + " FROM validation_task ORDER BY id DESC")
    List<ValidationTask> findTasks();
    @Update("""
            UPDATE validation_task SET status=#{row.status},last_error=#{row.lastError},version=version+1
            WHERE id=#{row.id} AND version=#{version}
            """)
    int updateTask(@Param("row") TaskRow row, @Param("version") long version);

    @Insert("""
            INSERT INTO validation_run(task_id,run_no,status,planned_keys,scanned_keys,compared_keys,difference_count,degraded_count,
              unverifiable_count,inconclusive_count,started_at,finished_at,summary_json)
            VALUES(#{taskId},#{runNo},#{status},#{plannedKeys},#{scannedKeys},#{comparedKeys},#{differenceCount},#{degradedCount},
              #{unverifiableCount},#{inconclusiveCount},#{startedAt},#{finishedAt},CAST(#{summaryJson} AS JSON))
            ON DUPLICATE KEY UPDATE status=VALUES(status),planned_keys=VALUES(planned_keys),scanned_keys=VALUES(scanned_keys),compared_keys=VALUES(compared_keys),
              difference_count=VALUES(difference_count),degraded_count=VALUES(degraded_count),
              unverifiable_count=VALUES(unverifiable_count),inconclusive_count=VALUES(inconclusive_count),
              finished_at=VALUES(finished_at),summary_json=VALUES(summary_json)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertRun(RunRow row);
    @Select("SELECT " + RUN_COLUMNS + " FROM validation_run WHERE task_id=#{taskId} ORDER BY id DESC LIMIT 1")
    ValidationRun findLatestRun(long taskId);
    @Insert("""
            INSERT INTO validation_difference(run_id,difference_type,key_hash,key_name,redis_type,source_size,target_size,
              source_ttl_seconds,target_ttl_seconds,comparison_level,degraded_reason)
            VALUES(#{runId},#{differenceType},#{keyHash},#{keyName},#{redisType},#{sourceSize},#{targetSize},#{sourceTtlSeconds},
              #{targetTtlSeconds},#{comparisonLevel},#{degradedReason})
            """)
    void insertDifference(DifferenceRow row);
    @Select("""
            SELECT id,run_id,difference_type,key_hash,key_name,redis_type,source_size,target_size,source_ttl_seconds,target_ttl_seconds,
              comparison_level,degraded_reason,created_at FROM validation_difference
            WHERE run_id=#{runId} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<ValidationDifference> findDifferences(@Param("runId") long runId, @Param("offset") int offset,
            @Param("size") int size);
    @Select("SELECT COUNT(*) FROM validation_difference WHERE run_id=#{runId}")
    long countDifferences(long runId);

    class TaskRow {
        public Long id, syncTaskId;
        public String taskNo, strictness, includePatternsJson, excludePatternsJson, sampleSeed, samplingMode, status,
                lastError;
        public long sourceClusterId, targetClusterId, ttlToleranceSeconds, largeKeyThresholdBytes, maxDeepCompareBytes;
        public int sourceDb, targetDb, sampleLimit, chunkBytes, maxElementsPerKey;
        public Double samplePercentage;
        static TaskRow from(ValidationTask x) {
            TaskRow r = new TaskRow();
            r.id = x.id();
            r.taskNo = x.taskNo();
            r.syncTaskId = x.syncTaskId();
            r.sourceClusterId = x.sourceClusterId();
            r.targetClusterId = x.targetClusterId();
            r.sourceDb = x.sourceDb();
            r.targetDb = x.targetDb();
            r.strictness = x.strictness().name();
            r.includePatternsJson = x.includePatternsJson();
            r.excludePatternsJson = x.excludePatternsJson();
            r.sampleSeed = x.sampleSeed();
            r.samplingMode = x.samplingMode().name();
            r.sampleLimit = x.sampleLimit();
            r.samplePercentage = x.samplePercentage();
            r.ttlToleranceSeconds = x.ttlToleranceSeconds();
            r.largeKeyThresholdBytes = x.largeKeyThresholdBytes();
            r.maxDeepCompareBytes = x.maxDeepCompareBytes();
            r.chunkBytes = x.chunkBytes();
            r.maxElementsPerKey = x.maxElementsPerKey();
            r.status = x.status().name();
            r.lastError = x.lastError();
            return r;
        }
    }
    class RunRow {
        public Long id;
        public long taskId, plannedKeys, scannedKeys, comparedKeys, differenceCount, degradedCount, unverifiableCount,
                inconclusiveCount;
        public String runNo, status, summaryJson;
        public Instant startedAt, finishedAt;
        static RunRow from(ValidationRun x) {
            RunRow r = new RunRow();
            r.id = x.id();
            r.taskId = x.taskId();
            r.runNo = x.runNo();
            r.status = x.status();
            r.plannedKeys = x.plannedKeys();
            r.scannedKeys = x.scannedKeys();
            r.comparedKeys = x.comparedKeys();
            r.differenceCount = x.differenceCount();
            r.degradedCount = x.degradedCount();
            r.unverifiableCount = x.unverifiableCount();
            r.inconclusiveCount = x.inconclusiveCount();
            r.startedAt = x.startedAt();
            r.finishedAt = x.finishedAt();
            r.summaryJson = x.summaryJson();
            return r;
        }
    }
    class DifferenceRow {
        public long runId;
        public String differenceType, keyHash, keyName, redisType, comparisonLevel, degradedReason;
        public Long sourceSize, targetSize, sourceTtlSeconds, targetTtlSeconds;
        static DifferenceRow from(ValidationDifference x) {
            DifferenceRow r = new DifferenceRow();
            r.runId = x.runId();
            r.differenceType = x.differenceType().name();
            r.keyHash = x.keyHash();
            r.keyName = x.keyName();
            r.redisType = x.redisType();
            r.sourceSize = x.sourceSize();
            r.targetSize = x.targetSize();
            r.sourceTtlSeconds = x.sourceTtlSeconds();
            r.targetTtlSeconds = x.targetTtlSeconds();
            r.comparisonLevel = x.comparisonLevel();
            r.degradedReason = x.degradedReason();
            return r;
        }
    }
}
