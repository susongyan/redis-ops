package io.github.susongyan.redisops.worker.persistence;

import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkerRegistryMapper {
    @Insert("""
            INSERT INTO sync_worker_registry(instance_name,worker_ip,worker_port,version,started_at,heartbeat_at)
            VALUES(#{instanceName},#{workerIp},#{workerPort},#{version},#{startedAt},CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE worker_ip=VALUES(worker_ip),worker_port=VALUES(worker_port),
              version=VALUES(version),heartbeat_at=CURRENT_TIMESTAMP(3)
            """)
    int upsert(@Param("instanceName") String instanceName, @Param("workerIp") String workerIp,
            @Param("workerPort") int workerPort, @Param("version") String version,
            @Param("startedAt") java.time.Instant startedAt);
}
