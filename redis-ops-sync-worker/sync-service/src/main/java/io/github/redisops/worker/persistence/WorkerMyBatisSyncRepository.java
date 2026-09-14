package io.github.redisops.worker.persistence;

import io.github.redisops.sync.contract.SyncContractStatus;
import io.github.redisops.worker.domain.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Worker-owned persistence adapter for the shared synchronization control schema. */
@Repository
public class WorkerMyBatisSyncRepository implements WorkerSyncStatePort, WorkerSyncExecutionPort {
    private final WorkerSyncMapper mapper;
    private final String workerIp;

    public WorkerMyBatisSyncRepository(WorkerSyncMapper mapper,
            @org.springframework.beans.factory.annotation.Value("${sync.engine.worker-ip:}") String workerIp) {
        this.mapper = mapper;
        this.workerIp = resolveIp(workerIp);
    }

    static String resolveIp(String configured) {
        String host = configured == null ? "" : configured.trim();
        if (host.isEmpty()) {
            try {
                var address = java.net.InetAddress.getLocalHost();
                return usable(address) ? address.getHostAddress() : null;
            } catch (java.net.UnknownHostException ignored) {
                return null;
            }
        }
        // Only literal addresses: never resolve an arbitrary configured hostname through DNS.
        if (!host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}") && !host.matches("[0-9a-fA-F]*:[0-9a-fA-F:]+"))
            throw new IllegalArgumentException("worker-ip must be a literal IPv4 or IPv6 address");
        try {
            var address = java.net.InetAddress.getByName(host);
            if (!usable(address))
                throw new IllegalArgumentException("worker-ip must be a non-loopback unicast address");
            return address.getHostAddress();
        } catch (java.net.UnknownHostException ignored) {
            throw new IllegalArgumentException("worker-ip must be a valid IPv4 or IPv6 address");
        }
    }

    private static boolean usable(java.net.InetAddress address) {
        return !address.isLoopbackAddress() && !address.isAnyLocalAddress()
                && !address.isMulticastAddress() && !address.isLinkLocalAddress();
    }

    @Override
    public WorkerSyncTask get(long taskId) {
        WorkerSyncTask task = mapper.findTask(taskId);
        if (task == null)
            throw new IllegalArgumentException("sync task not found: " + taskId);
        return task;
    }

    @Override
    public Optional<WorkerSyncRuntime> runtime(long taskId) {
        return Optional.ofNullable(mapper.findRuntime(taskId));
    }

    @Override
    @Transactional
    public boolean claimRuntime(long taskId, String runtimeId, String owner, long leaseSeconds) {
        mapper.ensureRuntime(taskId, runtimeId);
        return mapper.claimRuntime(taskId, runtimeId, owner, leaseSeconds, workerIp) == 1;
    }

    @Override
    public boolean renewRuntime(long taskId, String owner, long leaseSeconds, String phase, long spoolBytes) {
        return mapper.renewRuntime(taskId, owner, leaseSeconds, phase, spoolBytes) == 1;
    }

    @Override
    public void releaseRuntime(long taskId, String owner, String phase, String error) {
        mapper.releaseRuntime(taskId, owner, phase, error);
    }

    @Override
    public List<WorkerSyncTask> findExpiredRecoverableTasks(int limit) {
        return mapper.findExpiredRecoverableTasks(Math.max(1, Math.min(limit, 100)));
    }

    @Override
    public boolean canTransitionTo(long taskId, SyncContractStatus status) {
        return WorkerSyncTransitionPolicy.canTransition(get(taskId).status(), status);
    }

    @Override
    @Transactional
    public void engineTransition(long taskId, long version, SyncContractStatus status, Long rpoSeconds,
            String blockedReason, String error, String message, String operator) {
        WorkerSyncTask previous = get(taskId);
        if (!WorkerSyncTransitionPolicy.canTransition(previous.status(), status))
            throw new IllegalStateException("illegal engine transition: " + previous.status() + " -> " + status);
        if (status == SyncContractStatus.CAUGHT_UP && rpoSeconds == null)
            throw new IllegalArgumentException("RPO is required when caught up");
        if (mapper.transitionTask(taskId, version, status.name(), rpoSeconds, blockedReason, error) != 1)
            throw new IllegalStateException("sync task version conflict: " + taskId);
        mapper.insertEvent(taskId, previous.status().name(), status.name(), operator, message);
    }

    @Override
    public void appendEngineEvent(long taskId, String message, String operator) {
        WorkerSyncTask task = get(taskId);
        mapper.insertEvent(taskId, task.status().name(), task.status().name(), operator, message);
    }

    @Override
    public void appendTaskEvent(long taskId, String operator, String message) {
        appendEngineEvent(taskId, message, operator);
    }

    @Override
    public WorkerSyncPrecheckReport savePrecheck(WorkerSyncPrecheckReport report) {
        WorkerSyncMapper.PrecheckRow row = WorkerSyncMapper.PrecheckRow.from(report);
        mapper.insertPrecheck(row);
        return new WorkerSyncPrecheckReport(row.taskId, row.status, row.reportJson, row.checkedAt, row.validUntil);
    }

    @Override
    public void upsertFullProgress(WorkerSyncFullProgress progress) {
        mapper.upsertFullProgress(progress);
    }
    @Override
    public void upsertChannel(WorkerSyncChannelCheckpoint checkpoint) {
        mapper.upsertChannel(checkpoint);
    }
    @Override
    public void saveMetric(WorkerSyncMetricSnapshot metric) {
        mapper.insertMetric(metric);
    }

    @Override
    public void updateRuntimeObservation(WorkerRuntimeObservation observation) {
        if (mapper.updateRuntimeObservation(observation.taskId(), observation.owner(), observation.phase(),
                observation.targetFenceGeneration(), observation.fencePublishedAt(), observation.recoveryAction(),
                observation.error()) != 1)
            throw new IllegalStateException("runtime lease lost: " + observation.taskId());
    }
}
