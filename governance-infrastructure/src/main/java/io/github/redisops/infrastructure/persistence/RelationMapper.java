package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.relation.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface RelationMapper {
    String COLUMNS="id,name,relation_type,primary_cluster_id,standby_cluster_id,status,desired_rpo_seconds,description,version,created_at,updated_at";
    @Insert("INSERT INTO cluster_relation(name,relation_type,primary_cluster_id,standby_cluster_id,status,desired_rpo_seconds,description) VALUES(#{name},#{relationType},#{primaryClusterId},#{standbyClusterId},#{status},#{desiredRpoSeconds},#{description})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insert(Row row);
    @Select("SELECT "+COLUMNS+" FROM cluster_relation WHERE id=#{id} AND deleted_at IS NULL") ClusterRelation find(long id);
    @Select("SELECT "+COLUMNS+" FROM cluster_relation WHERE deleted_at IS NULL ORDER BY id DESC") List<ClusterRelation> findAll();
    @Update("UPDATE cluster_relation SET name=#{row.name},relation_type=#{row.relationType},primary_cluster_id=#{row.primaryClusterId},standby_cluster_id=#{row.standbyClusterId},status=#{row.status},desired_rpo_seconds=#{row.desiredRpoSeconds},description=#{row.description},version=version+1 WHERE id=#{row.id} AND version=#{version} AND deleted_at IS NULL") int update(@Param("row")Row row,@Param("version")long version);
    @Update("UPDATE cluster_relation SET deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=#{id} AND version=#{version} AND deleted_at IS NULL") int delete(@Param("id")long id,@Param("version")long version);
    class Row { public Long id; public String name,relationType,status,description;public long primaryClusterId,standbyClusterId,desiredRpoSeconds;static Row from(ClusterRelation x){var r=new Row();r.id=x.id();r.name=x.name();r.relationType=x.relationType().name();r.primaryClusterId=x.primaryClusterId();r.standbyClusterId=x.standbyClusterId();r.status=x.status().name();r.desiredRpoSeconds=x.desiredRpoSeconds();r.description=x.description();return r;} }
}
