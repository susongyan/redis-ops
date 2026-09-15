package io.github.susongyan.redisops.platform.domain.distribution;

import java.util.*;
import io.github.susongyan.redisops.platform.common.PageResult;

public interface DistributionRepository {
    record Lease(long clusterId, long taskId, String owner, long generation) {
    }
    DistributionTask create(DistributionSpec spec);
    DistributionTask get(long id);
    PageResult<DistributionTask> list(int page, int size);
    DistributionTask control(long id, long version, String action);
    Optional<Lease> claim(String owner);
    Optional<Lease> claimPreview(long clusterId, String owner);
    boolean renew(Lease lease);
    void release(Lease lease);
    Optional<DistributionCheckpoint> checkpoint(long taskId);
    boolean save(Lease lease, DistributionCheckpoint checkpoint, String status, String reason);
    PageResult<DistributionCounter.Entry> groups(long id, int page, int size);
    void cleanup(int days);
}
