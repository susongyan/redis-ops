package io.github.redisops.domain.asset;

import java.util.List;
import java.util.Optional;

public interface AssetRepository {
    ManagedApplication saveApplication(ManagedApplication application);
    Optional<ManagedApplication> findApplication(long id);
    List<ManagedApplication> findApplications();
    boolean updateApplication(ManagedApplication application, long expectedVersion);
    boolean deleteApplication(long id, long expectedVersion);
    void bind(ApplicationBinding binding);
    void unbind(long applicationId, long clusterId);
    List<ApplicationBinding> findBindingsByCluster(long clusterId);
    List<ApplicationBinding> findBindingsByApplication(long applicationId);
    List<RedisNode> findNodes(long clusterId);
    DiscoveryRun startDiscovery(long clusterId);
    Optional<DiscoveryRun> findDiscovery(long runId);
    void restartDiscovery(long runId);
    void completeDiscovery(long runId, long clusterId, List<RedisNode> nodes);
    void failDiscovery(long runId, String errorMessage);
    List<DiscoveryRun> findDiscoveries(long clusterId);
}
