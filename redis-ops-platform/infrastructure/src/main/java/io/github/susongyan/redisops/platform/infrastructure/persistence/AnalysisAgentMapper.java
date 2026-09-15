package io.github.susongyan.redisops.platform.infrastructure.persistence;

import io.github.susongyan.redisops.platform.domain.analysis.AnalysisAgentProfile;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AnalysisAgentMapper {
    @Insert("INSERT INTO analysis_agent_profile(name,protocol,endpoint,enabled,supported_types_json,timeout_ms,priority,version) VALUES(#{name},#{protocol},#{endpoint},#{enabled},CAST(#{supportedTypesJson} AS JSON),#{timeoutMs},#{priority},0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Row row);

    @Select("SELECT id,name,protocol,endpoint,enabled,CAST(supported_types_json AS CHAR) supportedTypesJson,timeout_ms timeoutMs,priority,version,created_at createdAt,updated_at updatedAt FROM analysis_agent_profile WHERE id=#{id}")
    AnalysisAgentProfile find(long id);

    @Select("<script>SELECT id,name,protocol,endpoint,enabled,CAST(supported_types_json AS CHAR) supportedTypesJson,timeout_ms timeoutMs,priority,version,created_at createdAt,updated_at updatedAt FROM analysis_agent_profile <if test='includeDisabled == false'>WHERE enabled=TRUE</if> ORDER BY priority ASC,id ASC</script>")
    List<AnalysisAgentProfile> findAll(@Param("includeDisabled") boolean includeDisabled);

    @Update("UPDATE analysis_agent_profile SET name=#{name},protocol=#{protocol},endpoint=#{endpoint},enabled=#{enabled},supported_types_json=CAST(#{supportedTypesJson} AS JSON),timeout_ms=#{timeoutMs},priority=#{priority},version=version+1 WHERE id=#{id} AND version=#{version}")
    int update(Row row);

    class Row {
        public Long id;
        public String name;
        public String protocol;
        public String endpoint;
        public boolean enabled;
        public String supportedTypesJson;
        public int timeoutMs;
        public int priority;
        public long version;
    }
}
