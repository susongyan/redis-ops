package io.github.redisops.infrastructure.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CollectorRunMapper {
    @Insert("INSERT INTO collector_run(cluster_id,collection_type,status,started_at) VALUES(#{clusterId},#{type},'RUNNING',#{startedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void start(Row row);

    @Update("UPDATE collector_run SET status=#{status},completed_at=CURRENT_TIMESTAMP(3),summary_json=CAST(#{summary} AS JSON),error_code=#{errorCode},error_message=#{errorMessage} WHERE id=#{id}")
    void finish(@Param("id") long id, @Param("status") String status, @Param("summary") String summary,
            @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    class Row {
        public Long id;
        public long clusterId;
        public String type;
        public Instant startedAt;
    }
}
