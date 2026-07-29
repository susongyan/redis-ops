package io.github.redisops.application.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.application.relation.ClusterRelationService;
import io.github.redisops.common.BusinessException;
import io.github.redisops.common.PageResult;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.audit.AuditRepository;
import io.github.redisops.domain.job.JobRepository;
import io.github.redisops.domain.relation.*;
import io.github.redisops.domain.sync.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SyncService {
    private final SyncRepository sync;
    private final ClusterRelationRepository relations;
    private final ClusterRelationService relationService;
    private final ClusterRepository clusters;
    private final JobRepository jobs;
    private final AuditRepository audits;
    private final ObjectMapper json;

    public SyncService(SyncRepository sync, ClusterRelationRepository relations,
            ClusterRelationService relationService, ClusterRepository clusters,
            JobRepository jobs, AuditRepository audits, ObjectMapper json) {
        this.sync = sync;
        this.relations = relations;
        this.relationService = relationService;
        this.clusters = clusters;
        this.jobs = jobs;
        this.audits = audits;
        this.json = json;
    }

    @Transactional
    public SyncTask create(Long relationId, Long sourceId, Long targetId, SyncPurpose purpose, SyncMode mode,
            Integer sourceDb, Integer targetDb, List<String> includes, List<String> excludes,
            Long rateLimitOps, Long bandwidthLimit, Long spoolLimit, Integer fullApplyConcurrency,
            Integer fullApplyPipelineSize, String operator) {
        return create(relationId, sourceId, targetId, purpose, mode, sourceDb, targetDb, includes, excludes,
                rateLimitOps, bandwidthLimit, spoolLimit, fullApplyConcurrency, fullApplyPipelineSize,
                null, null, null, operator);
    }

    @Transactional
    public SyncTask create(Long relationId, Long sourceId, Long targetId, SyncPurpose purpose, SyncMode mode,
            Integer sourceDb, Integer targetDb, List<String> includes, List<String> excludes,
            Long rateLimitOps, Long bandwidthLimit, Long spoolLimit, Integer fullApplyConcurrency,
            Integer fullApplyPipelineSize, Boolean allowDestructiveCommands, Boolean allowSafeSplit,
            Set<String> additionalBlockedCommands, String operator) {
        long source, target;
        SyncPurpose actualPurpose;
        if (relationId != null) {
            var relation = relationService.get(relationId);
            if (relation.status() != RelationStatus.ACTIVE)
                throw invalid("relation must be active");
            source = relation.primaryClusterId();
            target = relation.standbyClusterId();
            actualPurpose = SyncPurpose.DISASTER_RECOVERY;
        } else {
            if (sourceId == null || targetId == null)
                throw invalid("sourceClusterId and targetClusterId are required without relationId");
            source = sourceId;
            target = targetId;
            actualPurpose = purpose == null ? SyncPurpose.ADHOC : purpose;
        }
        if (mode != null && mode != SyncMode.FULL_AND_INCREMENTAL)
            throw invalid("new tasks only support FULL_AND_INCREMENTAL; INCREMENTAL is reserved for checkpoint resume");
        relationService.validatePair(source, target);
        RedisCluster sourceCluster = cluster(source), targetCluster = cluster(target);
        validateVersionDirection(relationId, sourceCluster.redisVersion(), targetCluster.redisVersion());
        int actualSourceDb = validateDb("sourceDb", sourceCluster.mode(), sourceDb);
        int actualTargetDb = validateDb("targetDb", targetCluster.mode(), targetDb);
        List<String> actualIncludes = patterns(includes, true), actualExcludes = patterns(excludes, false);
        long ops = positiveOrDefault(rateLimitOps, SyncTask.DEFAULT_RATE_LIMIT_OPS, "rateLimitOps");
        long bandwidth = positiveOrDefault(bandwidthLimit, SyncTask.DEFAULT_BANDWIDTH_LIMIT,
                "bandwidthLimitBytesPerSecond");
        long spool = positiveOrDefault(spoolLimit, SyncTask.DEFAULT_SPOOL_LIMIT, "spoolLimitBytes");
        int concurrency = boundedOrDefault(fullApplyConcurrency, SyncTask.DEFAULT_FULL_APPLY_CONCURRENCY,
                1, 64, "fullApplyConcurrency");
        int pipelineSize = boundedOrDefault(fullApplyPipelineSize, SyncTask.DEFAULT_FULL_APPLY_PIPELINE_SIZE,
                1, 10_000, "fullApplyPipelineSize");
        var commandPolicy = new SyncCommandPolicy(Boolean.TRUE.equals(allowDestructiveCommands),
                allowSafeSplit == null || allowSafeSplit, additionalBlockedCommands,
                SyncCommandPolicy.CURRENT_VERSION);
        var task = newTask(relationId, source, target, actualPurpose, actualSourceDb, actualTargetDb,
                toJson(actualIncludes), toJson(actualExcludes), toJson(commandPolicy), ops, bandwidth, spool,
                concurrency, pipelineSize);
        var saved = sync.saveTask(task, operator, "native Java sync task created");
        audit(operator, "SYNC_TASK_CREATE", "SYNC_TASK", saved.id());
        return saved;
    }

    public SyncTask get(long id) {
        return sync.findTask(id).orElseThrow(() -> BusinessException.notFound("sync task", id));
    }
    public List<SyncTask> list(Long relationId) {
        if (relationId != null)
            relationService.get(relationId);
        return sync.findTasks(relationId);
    }
    public PageResult<SyncTaskEvent> events(long id, int page, int size) {
        get(id);
        if (page < 1)
            throw invalid("page must be at least 1");
        int actualSize = Math.max(1, Math.min(size, 100));
        int offset = Math.multiplyExact(page - 1, actualSize);
        return new PageResult<>(sync.findEvents(id, offset, actualSize), sync.countEvents(id), page, actualSize);
    }
    public Optional<SyncRuntime> runtime(long id) {
        get(id);
        return sync.findRuntime(id);
    }

    public void appendEngineEvent(long id, String message, String engine) {
        get(id);
        sync.appendTaskEvent(id, engine, message);
    }
    public List<SyncChannelCheckpoint> channels(long id) {
        get(id);
        return sync.findChannels(id);
    }
    public Optional<SyncPrecheckReport> precheck(long id) {
        get(id);
        return sync.findLatestPrecheck(id);
    }
    public List<SyncMetricSnapshot> metrics(long id, int limit) {
        get(id);
        return sync.findMetrics(id, limit);
    }
    public List<SyncFullProgress> fullProgress(long id) {
        get(id);
        return sync.findFullProgress(id);
    }

    @Transactional
    public SyncTask requestPrecheck(long id, long version, String operator, String requestKey) {
        var old = get(id);
        if (!(old.status() == SyncTaskStatus.CREATED || old.status() == SyncTaskStatus.FAILED ||
                old.status() == SyncTaskStatus.BLOCKED || old.status() == SyncTaskStatus.READY))
            throw invalid("precheck is not allowed from " + old.status());
        var updated = replace(old, SyncTaskStatus.CHECKING, SyncAction.PRECHECK.name(), false, null, null, null, null);
        update(updated, version, operator, "precheck requested");
        enqueue(SyncAction.PRECHECK, id, requestKey, Map.of("taskId", id));
        audit(operator, "SYNC_PRECHECK_REQUEST", "SYNC_TASK", id);
        return get(id);
    }

    @Transactional
    public SyncTask requestStart(long id, long version, boolean writeFenced, String writeFenceNote,
            boolean allowTargetFlush, String confirmationTaskNo, String operator, String requestKey) {
        var old = get(id);
        if (old.status() != SyncTaskStatus.READY)
            throw invalid("task must be READY");
        var report = sync.findLatestPrecheck(id).orElseThrow(() -> invalid("a successful precheck is required"));
        if (!report.validAt(Instant.now()))
            throw invalid("precheck report is missing, failed or older than 10 minutes");
        if (!writeFenced || writeFenceNote == null || writeFenceNote.isBlank())
            throw invalid("writeFenced=true and writeFenceNote are required");
        if (!allowTargetFlush || !old.taskNo().equals(confirmationTaskNo))
            throw invalid("target flush requires allowTargetFlush=true and exact confirmationTaskNo");
        String epoch = UUID.randomUUID().toString();
        var updated = replace(old, SyncTaskStatus.STARTING, SyncAction.START.name(), true, writeFenceNote, null, epoch,
                null);
        update(updated, version, operator, "full sync start confirmed; target reset authorized");
        enqueue(SyncAction.START, id, requestKey,
                Map.of("taskId", id, "allowTargetFlush", true, "fullSyncEpoch", epoch));
        audit(operator, "SYNC_START_REQUEST", "SYNC_TASK", id);
        return get(id);
    }

    @Transactional
    public SyncTask requestPause(long id, long version, String operator, String requestKey) {
        var old = get(id);
        if (!(old.status() == SyncTaskStatus.FULL_SYNCING || old.status() == SyncTaskStatus.INCR_SYNCING ||
                old.status() == SyncTaskStatus.CAUGHT_UP))
            throw invalid("task is not running");
        update(replace(old, SyncTaskStatus.PAUSING, SyncAction.PAUSE.name(), old.writeFenced(),
                old.writeFenceNote(), null, null, null), version, operator, "pause requested");
        enqueue(SyncAction.PAUSE, id, requestKey, Map.of("taskId", id));
        return get(id);
    }

    @Transactional
    public SyncTask requestResume(long id, long version, String operator, String requestKey) {
        var old = get(id);
        if (!(old.status() == SyncTaskStatus.PAUSED || old.status() == SyncTaskStatus.BLOCKED))
            throw invalid("task must be PAUSED or BLOCKED");
        update(replace(old, SyncTaskStatus.RESUMING, SyncAction.RESUME.name(), old.writeFenced(),
                old.writeFenceNote(), null, null, null), version, operator, "resume requested");
        enqueue(SyncAction.RESUME, id, requestKey, Map.of("taskId", id));
        return get(id);
    }

    @Transactional
    public SyncTask requestFinish(long id, long version, boolean sourceWriteFenced, String note,
            String operator, String requestKey) {
        var old = get(id);
        if (!(old.status() == SyncTaskStatus.CAUGHT_UP || old.status() == SyncTaskStatus.INCR_SYNCING))
            throw invalid("task must be caught up or incrementally syncing");
        if (!sourceWriteFenced || note == null || note.isBlank())
            throw invalid("sourceWriteFenced=true and a fence note are required");
        update(replace(old, SyncTaskStatus.STOPPING, SyncAction.FINISH.name(), old.writeFenced(),
                old.writeFenceNote(), null, null, null), version, operator, "final offset drain requested: " + note);
        enqueue(SyncAction.FINISH, id, requestKey, Map.of("taskId", id, "sourceWriteFenced", true, "note", note));
        return get(id);
    }

    @Transactional
    public SyncTask requestCancel(long id, long version, String operator, String requestKey) {
        var old = get(id);
        if (old.status().terminal())
            throw invalid("task is already terminal");
        update(replace(old, SyncTaskStatus.CANCELLED, SyncAction.CANCEL.name(), old.writeFenced(),
                old.writeFenceNote(), null, null, "cancelled by operator"), version, operator, "cancel requested");
        enqueue(SyncAction.CANCEL, id, requestKey, Map.of("taskId", id));
        return get(id);
    }

    @Transactional
    public SyncTask updateLimits(long id, long version, long rateLimit, long bandwidth, long spoolLimit,
            Integer fullApplyConcurrency, Integer fullApplyPipelineSize, String operator, String requestKey) {
        var old = get(id);
        long ops = positiveOrDefault(rateLimit, 0, "rateLimitOps");
        long bytes = positiveOrDefault(bandwidth, 0, "bandwidthLimitBytesPerSecond");
        long spool = positiveOrDefault(spoolLimit, 0, "spoolLimitBytes");
        int concurrency = boundedOrDefault(fullApplyConcurrency, old.fullApplyConcurrency(),
                1, 64, "fullApplyConcurrency");
        int pipelineSize = boundedOrDefault(fullApplyPipelineSize, old.fullApplyPipelineSize(),
                1, 10_000, "fullApplyPipelineSize");
        if ((concurrency != old.fullApplyConcurrency() || pipelineSize != old.fullApplyPipelineSize())
                && running(old.status()))
            throw invalid("full apply tuning cannot be changed after synchronization has started");
        var changed = new SyncTask(old.id(), old.taskNo(), old.relationId(), old.sourceClusterId(),
                old.targetClusterId(),
                old.purpose(), old.syncMode(), old.status(), old.toolType(), old.sourceDb(), old.targetDb(),
                old.includePatternsJson(), old.excludePatternsJson(), old.commandPolicyJson(), ops, bytes, spool,
                concurrency, pipelineSize,
                SyncAction.RATE_LIMIT.name(),
                old.writeFenced(), old.writeFenceNote(), old.blockedReason(), old.fullSyncEpoch(), old.lastRpoSeconds(),
                old.lastError(), old.version(), old.createdAt(), Instant.now(), old.finishedAt());
        update(changed, version, operator, "sync limits updated");
        if (runnerActive(old.status()))
            enqueue(SyncAction.RATE_LIMIT, id, requestKey, Map.of("taskId", id, "rateLimitOps", ops,
                    "bandwidthLimitBytesPerSecond", bytes, "spoolLimitBytes", spool,
                    "fullApplyConcurrency", concurrency, "fullApplyPipelineSize", pipelineSize));
        return get(id);
    }

    @Transactional
    public SyncTask engineTransition(long id, long version, SyncTaskStatus target, Long rpo, String blockedReason,
            String error, String message, String engine) {
        var old = get(id);
        if (!old.status().canTransitionTo(target))
            throw invalid("illegal engine transition: " + old.status() + " -> " + target);
        if (target == SyncTaskStatus.CAUGHT_UP && rpo == null)
            throw invalid("RPO is required when caught up");
        var changed = replace(old, target, null, old.writeFenced(), old.writeFenceNote(), blockedReason, null, error);
        if (rpo != null)
            changed = new SyncTask(changed.id(), changed.taskNo(), changed.relationId(), changed.sourceClusterId(),
                    changed.targetClusterId(), changed.purpose(), changed.syncMode(), changed.status(),
                    changed.toolType(),
                    changed.sourceDb(), changed.targetDb(), changed.includePatternsJson(),
                    changed.excludePatternsJson(), changed.commandPolicyJson(),
                    changed.rateLimitOps(), changed.bandwidthLimitBytesPerSecond(), changed.spoolLimitBytes(),
                    changed.fullApplyConcurrency(), changed.fullApplyPipelineSize(),
                    changed.desiredAction(), changed.writeFenced(), changed.writeFenceNote(), changed.blockedReason(),
                    changed.fullSyncEpoch(), rpo, changed.lastError(), changed.version(), changed.createdAt(),
                    changed.updatedAt(), changed.finishedAt());
        update(changed, version, engine, message);
        if (target == SyncTaskStatus.FINISHED)
            completeDrain(id);
        return get(id);
    }

    @Transactional
    public Switchover startSwitchover(long relationId, String operator) {
        var relation = relationService.get(relationId);
        if (relation.status() != RelationStatus.ACTIVE)
            throw invalid("relation must be active");
        if (sync.countActiveSwitchovers(relationId) > 0)
            throw inUse("relation already has an active switchover");
        var task = sync.findLatestTask(relationId).orElseThrow(() -> invalid("relation has no sync task"));
        if (task.status() != SyncTaskStatus.CAUGHT_UP)
            throw invalid("latest sync task must be CAUGHT_UP");
        verifyStableRpo(task, relation.desiredRpoSeconds());
        var switching = new ClusterRelation(relation.id(), relation.name(), relation.relationType(),
                relation.primaryClusterId(), relation.standbyClusterId(), RelationStatus.SWITCHING,
                relation.desiredRpoSeconds(), relation.description(), relation.version(), relation.createdAt(),
                Instant.now());
        if (!relations.update(switching, relation.version()))
            concurrent();
        var result = sync.saveSwitchover(new Switchover(null, relationId, relation.primaryClusterId(),
                relation.standbyClusterId(), task.id(), null, SwitchoverStatus.WAITING_SOURCE_FENCE,
                operator, false, null, null, 0, Instant.now(), Instant.now(), null));
        audit(operator, "SWITCHOVER_START", "SWITCHOVER", result.id());
        return result;
    }

    @Transactional
    public Switchover confirmSourceFence(long id, long version, String note, String operator, String requestKey) {
        var sw = getSwitchover(id);
        if (sw.status() != SwitchoverStatus.WAITING_SOURCE_FENCE)
            throw invalid("switchover is not waiting for source fence");
        if (note == null || note.isBlank())
            throw invalid("source fence note is required");
        var task = get(sw.stoppedTaskId());
        requestFinish(task.id(), task.version(), true, note, operator, requestKey);
        var draining = new Switchover(sw.id(), sw.relationId(), sw.oldPrimaryClusterId(), sw.oldStandbyClusterId(),
                sw.stoppedTaskId(), sw.reverseTaskId(), SwitchoverStatus.DRAINING, sw.operator(),
                true, note, null, version, sw.createdAt(), Instant.now(), null);
        if (!sync.updateSwitchover(draining, version))
            concurrent();
        audit(operator, "SWITCHOVER_SOURCE_FENCE", "SWITCHOVER", id);
        return getSwitchover(id);
    }

    public Switchover getSwitchover(long id) {
        return sync.findSwitchover(id).orElseThrow(() -> BusinessException.notFound("switchover", id));
    }
    public List<Switchover> switchovers(long relationId) {
        relationService.get(relationId);
        return sync.findSwitchovers(relationId);
    }

    @Transactional
    public Switchover confirm(long id, long version, String operator) {
        var sw = getSwitchover(id);
        waitingExternal(sw);
        var relation = relationService.get(sw.relationId());
        if (relation.status() != RelationStatus.SWITCHING || relation.primaryClusterId() != sw.oldPrimaryClusterId() ||
                relation.standbyClusterId() != sw.oldStandbyClusterId())
            throw invalid("relation direction changed unexpectedly");
        var swapped = new ClusterRelation(relation.id(), relation.name(), relation.relationType(),
                relation.standbyClusterId(), relation.primaryClusterId(), RelationStatus.ACTIVE,
                relation.desiredRpoSeconds(), relation.description(), relation.version(), relation.createdAt(),
                Instant.now());
        if (!relations.update(swapped, relation.version()))
            concurrent();
        var oldTask = get(sw.stoppedTaskId());
        var reverse = sync.saveTask(newTask(relation.id(), relation.standbyClusterId(), relation.primaryClusterId(),
                SyncPurpose.DISASTER_RECOVERY, oldTask.targetDb(), oldTask.sourceDb(), oldTask.includePatternsJson(),
                oldTask.excludePatternsJson(), oldTask.commandPolicyJson(), oldTask.rateLimitOps(),
                oldTask.bandwidthLimitBytesPerSecond(),
                oldTask.spoolLimitBytes(), oldTask.fullApplyConcurrency(), oldTask.fullApplyPipelineSize()), operator,
                "reverse full-sync task created; requires precheck and target flush confirmation");
        var done = new Switchover(sw.id(), sw.relationId(), sw.oldPrimaryClusterId(), sw.oldStandbyClusterId(),
                sw.stoppedTaskId(), reverse.id(), SwitchoverStatus.CONFIRMED, sw.operator(),
                sw.sourceWriteFenced(), sw.sourceFenceNote(), null, version, sw.createdAt(), Instant.now(),
                Instant.now());
        if (!sync.updateSwitchover(done, version))
            concurrent();
        audit(operator, "SWITCHOVER_CONFIRM", "SWITCHOVER", id);
        return getSwitchover(id);
    }

    @Transactional
    public Switchover cancel(long id, long version, String operator) {
        var sw = getSwitchover(id);
        if (!(sw.status() == SwitchoverStatus.WAITING_SOURCE_FENCE || sw.status() == SwitchoverStatus.DRAINING ||
                sw.status() == SwitchoverStatus.WAITING_EXTERNAL_SWITCH))
            throw invalid("switchover cannot be cancelled from " + sw.status());
        var relation = relationService.get(sw.relationId());
        var restored = new ClusterRelation(relation.id(), relation.name(), relation.relationType(),
                sw.oldPrimaryClusterId(), sw.oldStandbyClusterId(), RelationStatus.ACTIVE,
                relation.desiredRpoSeconds(), relation.description(), relation.version(), relation.createdAt(),
                Instant.now());
        if (!relations.update(restored, relation.version()))
            concurrent();
        var cancelled = new Switchover(sw.id(), sw.relationId(), sw.oldPrimaryClusterId(), sw.oldStandbyClusterId(),
                sw.stoppedTaskId(), null, SwitchoverStatus.CANCELLED, sw.operator(), sw.sourceWriteFenced(),
                sw.sourceFenceNote(), null, version, sw.createdAt(), Instant.now(), null);
        if (!sync.updateSwitchover(cancelled, version))
            concurrent();
        audit(operator, "SWITCHOVER_CANCEL", "SWITCHOVER", id);
        return getSwitchover(id);
    }

    private void completeDrain(long taskId) {
        sync.findActiveSwitchoverByTask(taskId).filter(x -> x.status() == SwitchoverStatus.DRAINING).ifPresent(sw -> {
            var waiting = new Switchover(sw.id(), sw.relationId(), sw.oldPrimaryClusterId(), sw.oldStandbyClusterId(),
                    sw.stoppedTaskId(), sw.reverseTaskId(), SwitchoverStatus.WAITING_EXTERNAL_SWITCH,
                    sw.operator(), sw.sourceWriteFenced(), sw.sourceFenceNote(), null, sw.version(),
                    sw.createdAt(), Instant.now(), null);
            if (!sync.updateSwitchover(waiting, sw.version()))
                concurrent();
        });
    }

    private void verifyStableRpo(SyncTask task, long desiredRpo) {
        List<SyncMetricSnapshot> recent = sync.findMetrics(task.id(), 100);
        Map<String, List<SyncMetricSnapshot>> byChannel = recent.stream()
                .filter(x -> x.channelId() != null).collect(Collectors.groupingBy(SyncMetricSnapshot::channelId));
        if (byChannel.isEmpty() || byChannel.values().stream().anyMatch(x -> x.size() < 3))
            throw invalid("every sync channel needs three recent RPO samples");
        Instant freshAfter = Instant.now().minusSeconds(5);
        boolean invalid = byChannel.values().stream().anyMatch(samples -> {
            List<SyncMetricSnapshot> latest = samples.stream()
                    .sorted(Comparator.comparing(SyncMetricSnapshot::collectedAt).reversed())
                    .limit(3).toList();
            if (latest.stream().anyMatch(x -> x.collectedAt().isBefore(freshAfter)
                    || x.timestampLagSeconds() == null
                    || x.timestampLagSeconds() < -2
                    || x.timestampLagSeconds() > desiredRpo))
                return true;
            for (int i = 1; i < latest.size(); i++)
                if (Duration.between(latest.get(i).collectedAt(), latest.get(i - 1).collectedAt()).toMillis() > 2500)
                    return true;
            return false;
        });
        if (invalid)
            throw invalid("RPO is stale or exceeds relation target");
    }

    private SyncTask newTask(Long relationId, long source, long target, SyncPurpose purpose, int sourceDb, int targetDb,
            String includes, String excludes, String commandPolicy, long ops, long bandwidth, long spool,
            int fullApplyConcurrency, int fullApplyPipelineSize) {
        var now = Instant.now();
        return new SyncTask(null,
                "SYNC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(),
                relationId, source, target, purpose, SyncMode.FULL_AND_INCREMENTAL, SyncTaskStatus.CREATED,
                "NATIVE_JAVA",
                sourceDb, targetDb, includes, excludes, commandPolicy, ops, bandwidth, spool, fullApplyConcurrency,
                fullApplyPipelineSize, null, false, null, null, null,
                null, null, 0, now, now, null);
    }

    private SyncTask replace(SyncTask old, SyncTaskStatus status, String desired, boolean fenced, String fenceNote,
            String blocked, String epoch, String error) {
        return new SyncTask(old.id(), old.taskNo(), old.relationId(), old.sourceClusterId(), old.targetClusterId(),
                old.purpose(), old.syncMode(), status, old.toolType(), old.sourceDb(), old.targetDb(),
                old.includePatternsJson(), old.excludePatternsJson(), old.commandPolicyJson(), old.rateLimitOps(),
                old.bandwidthLimitBytesPerSecond(), old.spoolLimitBytes(), old.fullApplyConcurrency(),
                old.fullApplyPipelineSize(), desired, fenced, fenceNote, blocked,
                epoch == null ? old.fullSyncEpoch() : epoch, old.lastRpoSeconds(), error, old.version(),
                old.createdAt(),
                Instant.now(), status.terminal() ? Instant.now() : null);
    }

    private void update(SyncTask task, long version, String operator, String message) {
        if (!sync.updateTask(task, version, operator, message))
            concurrent();
        audit(operator, "SYNC_TASK_" + task.status().name(), "SYNC_TASK", task.id());
    }

    private void enqueue(SyncAction action, long taskId, String requestKey, Object payload) {
        jobs.enqueue("SYNC_" + action.name(), taskId, toJson(payload),
                "sync:" + taskId + ":" + action + ":" + requestKey);
    }

    private RedisCluster cluster(long id) {
        return clusters.findById(id).orElseThrow(() -> BusinessException.notFound("cluster", id));
    }
    private static int validateDb(String field, ClusterMode mode, Integer value) {
        if (mode == ClusterMode.CLUSTER) {
            if (value != null && value != 0)
                throw invalid(field + " must be 0 when the cluster mode is CLUSTER");
            return 0;
        }
        if (value == null)
            throw invalid(field + " must be selected for Standalone or Sentinel");
        if (value < 0)
            throw invalid(field + " cannot be negative");
        return value;
    }
    private static List<String> patterns(List<String> value, boolean include) {
        List<String> result = value == null || value.isEmpty()
                ? (include ? List.of("*") : List.of())
                : List.copyOf(value);
        if (result.size() > 100)
            throw invalid("include/exclude patterns support at most 100 entries");
        if (result.stream().anyMatch(x -> x == null || x.isBlank()))
            throw invalid("key patterns cannot be blank");
        return result;
    }
    private static long positiveOrDefault(Long value, long fallback, String field) {
        if (value == null)
            return fallback;
        if (value <= 0)
            throw invalid(field + " must be positive");
        return value;
    }
    private static int boundedOrDefault(Integer value, int fallback, int minimum, int maximum, String field) {
        int result = value == null ? fallback : value;
        if (result < minimum || result > maximum)
            throw invalid(field + " must be between " + minimum + " and " + maximum);
        return result;
    }
    private static boolean running(SyncTaskStatus status) {
        return !(status == SyncTaskStatus.CREATED || status == SyncTaskStatus.CHECKING ||
                status == SyncTaskStatus.READY || status == SyncTaskStatus.FAILED ||
                status == SyncTaskStatus.BLOCKED);
    }
    private static boolean runnerActive(SyncTaskStatus status) {
        return status == SyncTaskStatus.STARTING || status == SyncTaskStatus.FULL_SYNCING ||
                status == SyncTaskStatus.INCR_SYNCING || status == SyncTaskStatus.CAUGHT_UP ||
                status == SyncTaskStatus.PAUSING || status == SyncTaskStatus.PAUSED ||
                status == SyncTaskStatus.RESUMING || status == SyncTaskStatus.STOPPING;
    }
    private static void validateVersionDirection(Long relationId, String source, String target) {
        if (source == null || target == null)
            return;
        int[] a = version(source), b = version(target);
        if (relationId == null && (a[0] > b[0] || (a[0] == b[0] && a[1] > b[1])))
            throw invalid("temporary migration from newer Redis to older Redis is not certified");
    }
    private static int[] version(String value) {
        try {
            String[] p = value.split("[.-]");
            return new int[]{Integer.parseInt(p[0]), p.length > 1 ? Integer.parseInt(p[1]) : 0};
        } catch (RuntimeException ignored) {
            return new int[]{0, 0};
        }
    }
    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize sync payload", e);
        }
    }
    private void audit(String op, String action, String type, long id) {
        audits.append(op, action, type, Long.toString(id), "SUCCESS");
    }
    private static void waitingExternal(Switchover x) {
        if (x.status() != SwitchoverStatus.WAITING_EXTERNAL_SWITCH)
            throw invalid("switchover is not waiting for external switch");
    }
    private static BusinessException invalid(String m) {
        return new BusinessException("INVALID_ARGUMENT", m);
    }
    private static BusinessException inUse(String m) {
        return new BusinessException("RESOURCE_IN_USE", m);
    }
    private static void concurrent() {
        throw new BusinessException("CONCURRENT_MODIFICATION", "resource was modified, reload and retry");
    }
}
