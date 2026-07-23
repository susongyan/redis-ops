package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.asset.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisAssetRepository implements AssetRepository {
    private final AssetMapper mapper;
    public MyBatisAssetRepository(AssetMapper mapper) { this.mapper = mapper; }
    @Override public ManagedApplication saveApplication(ManagedApplication a) {
        AssetMapper.ApplicationRow r=applicationRow(a);
        mapper.insertApplication(r);return mapper.findApplication(r.id);
    }
    @Override public Optional<ManagedApplication> findApplication(long id) { return Optional.ofNullable(mapper.findApplication(id)); }
    @Override public List<ManagedApplication> findApplications() { return mapper.findApplications(); }
    @Override public boolean updateApplication(ManagedApplication a,long version){return mapper.updateApplication(applicationRow(a),version)==1;}
    @Override public boolean deleteApplication(long id,long version){return mapper.deleteApplication(id,version)==1;}
    @Override public void bind(ApplicationBinding binding) { mapper.bind(normalize(binding)); }
    @Override public void unbind(long applicationId,long clusterId) { mapper.unbind(applicationId,clusterId); }
    @Override public List<ApplicationBinding> findBindingsByCluster(long clusterId) { return mapper.findBindings(clusterId); }
    @Override public List<ApplicationBinding> findBindingsByApplication(long applicationId){return mapper.findApplicationBindings(applicationId);}
    @Override public List<RedisNode> findNodes(long clusterId) { return mapper.findNodes(clusterId); }
    @Override public DiscoveryRun startDiscovery(long clusterId) {
        AssetMapper.DiscoveryRow r=new AssetMapper.DiscoveryRow();r.clusterId=clusterId;mapper.startDiscovery(r);
        return new DiscoveryRun(r.id,clusterId,"RUNNING",java.time.Instant.now(),null,null,null);
    }
    @Override public Optional<DiscoveryRun> findDiscovery(long runId){return Optional.ofNullable(mapper.findDiscovery(runId));}
    @Override public void restartDiscovery(long runId){mapper.restartDiscovery(runId);}
    @Override @Transactional public void completeDiscovery(long runId,long clusterId,List<RedisNode> nodes) {
        mapper.deleteNodes(clusterId); nodes.stream().map(n -> new RedisNode(null,clusterId,n.host(),n.port(),n.nodeId(),n.role(),n.masterNodeId(),
                n.slotRanges()==null?"[]":n.slotRanges(),n.memoryBytes(),n.status())).forEach(mapper::insertNode);
        mapper.completeDiscovery(runId,nodes.size());
    }
    @Override public void failDiscovery(long runId,String errorMessage) { mapper.failDiscovery(runId,errorMessage); }
    @Override public List<DiscoveryRun> findDiscoveries(long clusterId) { return mapper.findDiscoveries(clusterId); }
    private static ApplicationBinding normalize(ApplicationBinding b) {
        return new ApplicationBinding(b.applicationId(),b.clusterId(),b.clientType(),b.clientVersion(),
                b.poolConfig()==null||b.poolConfig().isBlank()?"{}":b.poolConfig());
    }
    private static AssetMapper.ApplicationRow applicationRow(ManagedApplication a){
        AssetMapper.ApplicationRow r=new AssetMapper.ApplicationRow();r.id=a.id();r.code=a.code();r.name=a.name();r.owner=a.owner();r.businessLine=a.businessLine();r.status=a.status();r.version=a.version();return r;
    }
}
