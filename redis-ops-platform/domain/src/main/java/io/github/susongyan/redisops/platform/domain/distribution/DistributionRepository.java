package io.github.susongyan.redisops.platform.domain.distribution;

import java.util.*;
import io.github.susongyan.redisops.platform.common.PageResult;

public interface DistributionRepository {
    record Lease(long clusterId, long taskId, String owner, long generation) {
    }
    record ClaimedTask(DistributionTask task, Lease lease) {
    }
    DistributionTask create(DistributionSpec spec);
    /**
     * Inserts the task and claims its cluster in one transaction: no window where a fresh task
     * sits un-leased, so a foreign instance's sweep can never claim it first.
     */
    ClaimedTask createAndClaim(DistributionSpec spec, String owner);
    DistributionTask get(long id);
    PageResult<DistributionTask> list(int page, int size);
    DistributionTask control(long id, long version, String action);
    Optional<Lease> claim(String owner);
    /** Claims one specific task; empty when its cluster lease is held or the task is terminal. */
    Optional<Lease> claimTask(long taskId, String owner);
    Optional<Lease> claimPreview(long clusterId, String owner);
    boolean renew(Lease lease);
    void release(Lease lease);
    /** Returns a claim that made no progress to the queue, holding the lease as a cooldown. */
    void requeue(Lease lease, String reason);
    Optional<DistributionCheckpoint> checkpoint(long taskId);
    boolean save(Lease lease, DistributionCheckpoint checkpoint, String status, String reason);
    PageResult<DistributionCounter.Entry> groups(long id, int page, int size);
    void cleanup(int days);
}
