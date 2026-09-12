package io.github.redisops.sync.worker.persistence;

import io.github.redisops.sync.engine.WorkerControlJob;
import org.apache.ibatis.annotations.*;

@Mapper
interface WorkerControlJobMapper {
    @Update("""
            UPDATE async_job SET status='RUNNING',lease_owner=#{owner},
              lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL #{leaseSeconds} SECOND),attempts=attempts+1
            WHERE job_type=#{type} AND status IN ('PENDING','RETRY','RUNNING') AND next_run_at<=CURRENT_TIMESTAMP(3)
              AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(3)) ORDER BY id LIMIT 1
            """)
    int claim(@Param("type") String type, @Param("owner") String owner, @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE async_job SET status='RUNNING',lease_owner=#{owner},
              lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL #{leaseSeconds} SECOND),attempts=attempts+1
            WHERE id=(SELECT candidate.id FROM (SELECT j.id FROM async_job j LEFT JOIN sync_runtime r ON r.task_id=j.biz_id
              WHERE j.job_type=#{type} AND j.status IN ('PENDING','RETRY') AND j.next_run_at<=CURRENT_TIMESTAMP(3)
                AND (j.lease_until IS NULL OR j.lease_until<CURRENT_TIMESTAMP(3))
                AND (r.lease_owner=#{runtimeOwner} OR (#{allowExpiredRuntime}=TRUE AND
                  (r.task_id IS NULL OR r.lease_owner IS NULL OR r.lease_until<CURRENT_TIMESTAMP(3))))
              ORDER BY j.id LIMIT 1) candidate)
            """)
    int claimRouted(@Param("type") String type, @Param("owner") String owner,
            @Param("runtimeOwner") String runtimeOwner, @Param("leaseSeconds") long leaseSeconds,
            @Param("allowExpiredRuntime") boolean allowExpiredRuntime);

    @Select("SELECT id,job_type AS type,biz_id AS taskId,lease_owner AS leaseOwner FROM async_job WHERE lease_owner=#{owner} AND status='RUNNING' ORDER BY updated_at DESC LIMIT 1")
    WorkerControlJob findClaimed(@Param("owner") String owner);

    @Update("UPDATE async_job SET status='SUCCEEDED',lease_owner=NULL,lease_until=NULL WHERE id=#{id} AND lease_owner=#{owner} AND status='RUNNING'")
    int complete(@Param("id") long id, @Param("owner") String owner);

    @Update("""
            UPDATE async_job SET status=IF(attempts>=max_attempts,'FAILED','RETRY'),last_error=#{error},
              next_run_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL LEAST(60,POW(2,attempts)) SECOND),
              lease_owner=NULL,lease_until=NULL WHERE id=#{id} AND lease_owner=#{owner} AND status='RUNNING'
            """)
    int retryOrFail(@Param("id") long id, @Param("owner") String owner, @Param("error") String error);

    @Select("SELECT status FROM async_job WHERE id=#{id}")
    String status(@Param("id") long id);
}
