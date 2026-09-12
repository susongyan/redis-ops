package io.github.redisops.platform.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AnalysisRunMapper {
    @Insert("INSERT INTO analysis_run(request_id,analysis_type,resource_type,resource_id,protocol,provider,status,summary,confidence,evidence_json,recommendations_json) VALUES(#{requestId},#{analysisType},#{resourceType},#{resourceId},#{protocol},#{provider},#{status},#{summary},#{confidence},CAST(#{evidenceJson} AS JSON),CAST(#{recommendationsJson} AS JSON))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Row row);

    @Select("SELECT id,request_id requestId,analysis_type analysisType,resource_type resourceType,resource_id resourceId,protocol,provider,status,summary,confidence,CAST(evidence_json AS CHAR) evidenceJson,CAST(recommendations_json AS CHAR) recommendationsJson,created_at createdAt FROM analysis_run WHERE id=#{id}")
    Row find(long id);

    @Select("<script>SELECT id,request_id requestId,analysis_type analysisType,resource_type resourceType,resource_id resourceId,protocol,provider,status,summary,confidence,CAST(evidence_json AS CHAR) evidenceJson,CAST(recommendations_json AS CHAR) recommendationsJson,created_at createdAt FROM analysis_run WHERE (#{resourceType} IS NULL OR resource_type=#{resourceType}) AND (#{resourceId} IS NULL OR resource_id=#{resourceId}) ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Row> findPage(@Param("resourceType") String resourceType, @Param("resourceId") String resourceId,
            @Param("offset") int offset, @Param("size") int size);

    class Row {
        public Long id;
        public String requestId, analysisType, resourceType, resourceId, protocol, provider, status, summary;
        public double confidence;
        public String evidenceJson, recommendationsJson;
        public java.time.Instant createdAt;
    }
}
