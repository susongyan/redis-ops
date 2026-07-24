package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.job.AsyncJob;
import org.apache.ibatis.annotations.*;

@Mapper
public interface JobMapper {
    @Insert("""
            INSERT INTO async_job(job_type,biz_id,payload_json,status,idempotency_key)
            VALUES(#{jobType},#{bizId},CAST(#{payload} AS JSON),'PENDING',#{idempotencyKey})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(JobRow row);
    @Select("SELECT id,job_type,biz_id,CAST(payload_json AS CHAR) payload,status,idempotency_key,lease_owner,lease_until,attempts,max_attempts,last_error FROM async_job WHERE id=#{id}")
    AsyncJob findById(long id);
    @Select("SELECT id,job_type,biz_id,CAST(payload_json AS CHAR) payload,status,idempotency_key,lease_owner,lease_until,attempts,max_attempts,last_error FROM async_job WHERE idempotency_key=#{key}")
    AsyncJob findByKey(String key);
    @Update("""
            UPDATE async_job SET status='RUNNING',lease_owner=#{owner},lease_until=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{leaseSeconds} SECOND),attempts=attempts+1
            WHERE job_type=#{jobType} AND status IN ('PENDING','RETRY') AND next_run_at <= CURRENT_TIMESTAMP(3)
              AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP(3))
            ORDER BY id LIMIT 1
            """)
    int claim(@Param("jobType") String jobType, @Param("owner") String owner, @Param("leaseSeconds") long leaseSeconds);
    @Select("SELECT id,job_type,biz_id,CAST(payload_json AS CHAR) payload,status,idempotency_key,lease_owner,lease_until,attempts,max_attempts,last_error FROM async_job WHERE lease_owner=#{owner} AND status='RUNNING' ORDER BY updated_at DESC LIMIT 1")
    AsyncJob findClaimed(String owner);
    @Update("UPDATE async_job SET status='SUCCEEDED',lease_owner=NULL,lease_until=NULL WHERE id=#{id} AND lease_owner=#{owner} AND status='RUNNING'")
    int complete(@Param("id") long id, @Param("owner") String owner);
    @Update("""
            UPDATE async_job SET status=IF(attempts >= max_attempts,'FAILED','RETRY'),last_error=#{error},
              next_run_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL LEAST(60,POW(2,attempts)) SECOND),lease_owner=NULL,lease_until=NULL
            WHERE id=#{id} AND lease_owner=#{owner} AND status='RUNNING'
            """)
    int retryOrFail(@Param("id") long id, @Param("owner") String owner, @Param("error") String error);
    class JobRow {
        public Long id;
        public String jobType;
        public long bizId;
        public String payload, idempotencyKey;
    }
}
