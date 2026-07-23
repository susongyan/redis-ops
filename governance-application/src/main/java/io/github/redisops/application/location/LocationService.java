package io.github.redisops.application.location;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.audit.AuditRepository;
import io.github.redisops.domain.location.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class LocationService {
    private final LocationRepository locations; private final AuditRepository audits;
    public LocationService(LocationRepository locations,AuditRepository audits){this.locations=locations;this.audits=audits;}
    @Transactional public Region createRegion(String code,String name,ResourceStatus status,String description,String operator){
        require(code,"code");require(name,"name");var x=locations.saveRegion(new Region(null,code,name,status==null?ResourceStatus.ACTIVE:status,description,0,Instant.now(),Instant.now()));audit(operator,"REGION_CREATE","REGION",x.id());return x;}
    public Region getRegion(long id){return locations.findRegion(id).orElseThrow(()->BusinessException.notFound("region",id));}
    public List<Region> regions(){return locations.findRegions();}
    @Transactional public Region updateRegion(long id,long version,String code,String name,ResourceStatus status,String description,String operator){getRegion(id);require(code,"code");require(name,"name");var x=new Region(id,code,name,status==null?ResourceStatus.ACTIVE:status,description,version,null,Instant.now());if(!locations.updateRegion(x,version))concurrent();audit(operator,"REGION_UPDATE","REGION",id);return getRegion(id);}
    @Transactional public void deleteRegion(long id,long version,String operator){getRegion(id);if(locations.countIdcs(id)>0)inUse("region has active IDCs");if(!locations.deleteRegion(id,version))concurrent();audit(operator,"REGION_DELETE","REGION",id);}
    @Transactional public Idc createIdc(String code,String name,long regionId,String networkDomain,ResourceStatus status,String description,String operator){getRegion(regionId);require(code,"code");require(name,"name");var x=locations.saveIdc(new Idc(null,code,name,regionId,null,null,networkDomain,status==null?ResourceStatus.ACTIVE:status,description,0,Instant.now(),Instant.now()));audit(operator,"IDC_CREATE","IDC",x.id());return x;}
    public Idc getIdc(long id){return locations.findIdc(id).orElseThrow(()->BusinessException.notFound("idc",id));}
    public List<Idc> idcs(Long regionId){if(regionId!=null)getRegion(regionId);return locations.findIdcs(regionId);}
    @Transactional public Idc updateIdc(long id,long version,String code,String name,long regionId,String networkDomain,ResourceStatus status,String description,String operator){getIdc(id);getRegion(regionId);require(code,"code");require(name,"name");var x=new Idc(id,code,name,regionId,null,null,networkDomain,status==null?ResourceStatus.ACTIVE:status,description,version,null,Instant.now());if(!locations.updateIdc(x,version))concurrent();audit(operator,"IDC_UPDATE","IDC",id);return getIdc(id);}
    @Transactional public void deleteIdc(long id,long version,String operator){getIdc(id);if(locations.countClusters(id)>0)inUse("IDC is used by Redis clusters");if(!locations.deleteIdc(id,version))concurrent();audit(operator,"IDC_DELETE","IDC",id);}
    private void audit(String op,String action,String type,long id){audits.append(op,action,type,Long.toString(id),"SUCCESS");}
    private static void require(String x,String f){if(x==null||x.isBlank())throw new BusinessException("INVALID_ARGUMENT",f+" is required");}
    private static void concurrent(){throw new BusinessException("CONCURRENT_MODIFICATION","resource was modified, reload and retry");}
    private static void inUse(String m){throw new BusinessException("RESOURCE_IN_USE",m);}
}
