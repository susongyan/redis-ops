package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.location.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface LocationMapper {
    @Insert("INSERT INTO region(code,name,status,description) VALUES(#{code},#{name},#{status},#{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertRegion(RegionRow row);
    @Select("SELECT id,code,name,status,description,version,created_at,updated_at FROM region WHERE id=#{id} AND deleted_at IS NULL")
    Region findRegion(long id);
    @Select("SELECT id,code,name,status,description,version,created_at,updated_at FROM region WHERE deleted_at IS NULL ORDER BY code")
    List<Region> findRegions();
    @Update("UPDATE region SET code=#{row.code},name=#{row.name},status=#{row.status},description=#{row.description},version=version+1 WHERE id=#{row.id} AND version=#{version} AND deleted_at IS NULL")
    int updateRegion(@Param("row") RegionRow row, @Param("version") long version);
    @Update("UPDATE region SET deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=#{id} AND version=#{version} AND deleted_at IS NULL")
    int deleteRegion(@Param("id") long id, @Param("version") long version);
    @Select("SELECT COUNT(*) FROM idc WHERE region_id=#{id} AND deleted_at IS NULL")
    long countIdcs(long id);

    @Insert("INSERT INTO idc(code,name,region_id,network_domain,status,description) VALUES(#{code},#{name},#{regionId},#{networkDomain},#{status},#{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertIdc(IdcRow row);
    @Select("SELECT i.id,i.code,i.name,i.region_id,r.code region_code,r.name region_name,i.network_domain,i.status,i.description,i.version,i.created_at,i.updated_at FROM idc i JOIN region r ON r.id=i.region_id WHERE i.id=#{id} AND i.deleted_at IS NULL")
    Idc findIdc(long id);
    @Select("<script>SELECT i.id,i.code,i.name,i.region_id,r.code region_code,r.name region_name,i.network_domain,i.status,i.description,i.version,i.created_at,i.updated_at FROM idc i JOIN region r ON r.id=i.region_id WHERE i.deleted_at IS NULL <if test='regionId != null'>AND i.region_id=#{regionId}</if> ORDER BY r.code,i.code</script>")
    List<Idc> findIdcs(Long regionId);
    @Update("UPDATE idc SET code=#{row.code},name=#{row.name},region_id=#{row.regionId},network_domain=#{row.networkDomain},status=#{row.status},description=#{row.description},version=version+1 WHERE id=#{row.id} AND version=#{version} AND deleted_at IS NULL")
    int updateIdc(@Param("row") IdcRow row, @Param("version") long version);
    @Update("UPDATE idc SET deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=#{id} AND version=#{version} AND deleted_at IS NULL")
    int deleteIdc(@Param("id") long id, @Param("version") long version);
    @Select("SELECT COUNT(*) FROM redis_cluster WHERE idc_id=#{id} AND deleted_at IS NULL")
    long countClusters(long id);

    class RegionRow {
        public Long id;
        public String code, name, status, description;
        static RegionRow from(Region x) {
            var r = new RegionRow();
            r.id = x.id();
            r.code = x.code();
            r.name = x.name();
            r.status = x.status().name();
            r.description = x.description();
            return r;
        }
    }
    class IdcRow {
        public Long id, regionId;
        public String code, name, networkDomain, status, description;
        static IdcRow from(Idc x) {
            var r = new IdcRow();
            r.id = x.id();
            r.regionId = x.regionId();
            r.code = x.code();
            r.name = x.name();
            r.networkDomain = x.networkDomain();
            r.status = x.status().name();
            r.description = x.description();
            return r;
        }
    }
}
