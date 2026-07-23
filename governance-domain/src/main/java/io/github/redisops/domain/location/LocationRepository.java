package io.github.redisops.domain.location;

import java.util.List;
import java.util.Optional;

public interface LocationRepository {
    Region saveRegion(Region region); Optional<Region> findRegion(long id); List<Region> findRegions();
    boolean updateRegion(Region region,long version); boolean deleteRegion(long id,long version); long countIdcs(long regionId);
    Idc saveIdc(Idc idc); Optional<Idc> findIdc(long id); List<Idc> findIdcs(Long regionId);
    boolean updateIdc(Idc idc,long version); boolean deleteIdc(long id,long version); long countClusters(long idcId);
}
