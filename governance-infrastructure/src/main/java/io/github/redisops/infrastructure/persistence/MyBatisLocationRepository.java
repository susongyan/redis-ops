package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.location.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class MyBatisLocationRepository implements LocationRepository {
    private final LocationMapper mapper;
    public MyBatisLocationRepository(LocationMapper mapper) {
        this.mapper = mapper;
    }
    public Region saveRegion(Region x) {
        var r = LocationMapper.RegionRow.from(x);
        mapper.insertRegion(r);
        return mapper.findRegion(r.id);
    }
    public Optional<Region> findRegion(long id) {
        return Optional.ofNullable(mapper.findRegion(id));
    }
    public List<Region> findRegions() {
        return mapper.findRegions();
    }
    public boolean updateRegion(Region x, long v) {
        return mapper.updateRegion(LocationMapper.RegionRow.from(x), v) == 1;
    }
    public boolean deleteRegion(long id, long v) {
        return mapper.deleteRegion(id, v) == 1;
    }
    public long countIdcs(long id) {
        return mapper.countIdcs(id);
    }
    public Idc saveIdc(Idc x) {
        var r = LocationMapper.IdcRow.from(x);
        mapper.insertIdc(r);
        return mapper.findIdc(r.id);
    }
    public Optional<Idc> findIdc(long id) {
        return Optional.ofNullable(mapper.findIdc(id));
    }
    public List<Idc> findIdcs(Long regionId) {
        return mapper.findIdcs(regionId);
    }
    public boolean updateIdc(Idc x, long v) {
        return mapper.updateIdc(LocationMapper.IdcRow.from(x), v) == 1;
    }
    public boolean deleteIdc(long id, long v) {
        return mapper.deleteIdc(id, v) == 1;
    }
    public long countClusters(long id) {
        return mapper.countClusters(id);
    }
}
