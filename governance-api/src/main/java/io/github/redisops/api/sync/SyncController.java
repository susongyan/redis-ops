package io.github.redisops.api.sync;

import io.github.redisops.api.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.application.sync.SyncService;
import io.github.redisops.domain.sync.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class SyncController {
    private final SyncService service;
    private final IdempotencyService idempotency;
    public SyncController(SyncService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping("/api/v1/sync-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<SyncTask> create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SyncTaskRequest body, HttpServletRequest request) {
        String operator = operator(request);
        return wrap(idempotency.execute(operator, key, "SYNC_TASK_CREATE", body,
                () -> service.create(body.relationId, body.sourceClusterId, body.targetClusterId, body.purpose,
                        body.syncMode, body.sourceDb, body.targetDb, body.includePatterns, body.excludePatterns,
                        body.rateLimitOps, body.bandwidthLimitBytesPerSecond, body.spoolLimitBytes, operator),
                x -> x.id().toString(), id -> service.get(Long.parseLong(id))), request);
    }

    @GetMapping("/api/v1/sync-tasks")
    ApiResponse<List<SyncTask>> list(@RequestParam(required = false) Long relationId, HttpServletRequest request) {
        return wrap(service.list(relationId), request);
    }

    @GetMapping("/api/v1/sync-tasks/{id}")
    ApiResponse<TaskDetail> get(@PathVariable long id, HttpServletRequest request) {
        return wrap(new TaskDetail(service.get(id), service.events(id), service.runtime(id).orElse(null),
                service.channels(id), service.precheck(id).orElse(null), service.metrics(id, 100)), request);
    }

    @PostMapping("/api/v1/sync-tasks/{id}/prechecks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<SyncTask> precheck(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return action(request, key, "SYNC_PRECHECK", Map.of("taskId", id),
                () -> service.requestPrecheck(id, version, operator(request), key));
    }

    @GetMapping("/api/v1/sync-tasks/{id}/precheck")
    ApiResponse<SyncPrecheckReport> precheckResult(@PathVariable long id, HttpServletRequest request) {
        return wrap(service.precheck(id).orElse(null), request);
    }

    @PostMapping("/api/v1/sync-tasks/{id}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<SyncTask> start(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, @Valid @RequestBody StartRequest body,
            HttpServletRequest request) {
        return action(request, key, "SYNC_START", body, () -> service.requestStart(id, version, body.writeFenced,
                body.writeFenceNote, body.allowTargetFlush, body.confirmationTaskNo, operator(request), key));
    }

    @PostMapping("/api/v1/sync-tasks/{id}/pause")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<SyncTask> pause(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return action(request, key, "SYNC_PAUSE", Map.of("taskId", id),
                () -> service.requestPause(id, version, operator(request), key));
    }

    @PostMapping("/api/v1/sync-tasks/{id}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<SyncTask> resume(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return action(request, key, "SYNC_RESUME", Map.of("taskId", id),
                () -> service.requestResume(id, version, operator(request), key));
    }

    @PostMapping("/api/v1/sync-tasks/{id}/finish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<SyncTask> finish(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, @Valid @RequestBody FinishRequest body,
            HttpServletRequest request) {
        return action(request, key, "SYNC_FINISH", body, () -> service.requestFinish(id, version,
                body.sourceWriteFenced, body.sourceFenceNote, operator(request), key));
    }

    @PostMapping("/api/v1/sync-tasks/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<SyncTask> cancel(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return action(request, key, "SYNC_CANCEL", Map.of("taskId", id),
                () -> service.requestCancel(id, version, operator(request), key));
    }

    @PutMapping("/api/v1/sync-tasks/{id}/limits")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<SyncTask> limits(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, @Valid @RequestBody LimitRequest body,
            HttpServletRequest request) {
        return action(request, key, "SYNC_LIMITS", body, () -> service.updateLimits(id, version, body.rateLimitOps,
                body.bandwidthLimitBytesPerSecond, body.spoolLimitBytes, operator(request), key));
    }

    @GetMapping("/api/v1/sync-tasks/{id}/runtime")
    ApiResponse<SyncRuntime> runtime(@PathVariable long id, HttpServletRequest request) {
        return wrap(service.runtime(id).orElse(null), request);
    }
    @GetMapping("/api/v1/sync-tasks/{id}/channels")
    ApiResponse<List<SyncChannelCheckpoint>> channels(@PathVariable long id, HttpServletRequest request) {
        return wrap(service.channels(id), request);
    }
    @GetMapping("/api/v1/sync-tasks/{id}/metrics")
    ApiResponse<List<SyncMetricSnapshot>> metrics(@PathVariable long id,
            @RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        return wrap(service.metrics(id, limit), request);
    }

    @GetMapping("/api/v1/switchovers/{id}")
    ApiResponse<Switchover> getSwitchover(@PathVariable long id, HttpServletRequest request) {
        return wrap(service.getSwitchover(id), request);
    }
    @PostMapping("/api/v1/switchovers/{id}/source-fence-confirm")
    ApiResponse<Switchover> sourceFence(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version,
            @Valid @RequestBody SourceFenceRequest body, HttpServletRequest request) {
        String operator = operator(request);
        return wrap(idempotency.execute(operator, key, "SWITCHOVER_SOURCE_FENCE", body,
                () -> service.confirmSourceFence(id, version, body.note, operator, key), x -> x.id().toString(),
                rid -> service.getSwitchover(Long.parseLong(rid))), request);
    }
    @PostMapping("/api/v1/switchovers/{id}/confirm")
    ApiResponse<Switchover> confirm(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        String operator = operator(request);
        return wrap(idempotency.execute(operator, key, "SWITCHOVER_CONFIRM", id,
                () -> service.confirm(id, version, operator), x -> x.id().toString(),
                rid -> service.getSwitchover(Long.parseLong(rid))), request);
    }
    @PostMapping("/api/v1/switchovers/{id}/cancel")
    ApiResponse<Switchover> cancelSwitchover(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        String operator = operator(request);
        return wrap(idempotency.execute(operator, key, "SWITCHOVER_CANCEL", id,
                () -> service.cancel(id, version, operator), x -> x.id().toString(),
                rid -> service.getSwitchover(Long.parseLong(rid))), request);
    }

    public record SyncTaskRequest(Long relationId, Long sourceClusterId, Long targetClusterId, SyncPurpose purpose,
            SyncMode syncMode, Integer sourceDb, Integer targetDb,
            @Size(max = 100) List<String> includePatterns, @Size(max = 100) List<String> excludePatterns,
            @Positive Long rateLimitOps, @Positive Long bandwidthLimitBytesPerSecond,
            @Positive Long spoolLimitBytes) {
    }
    public record StartRequest(boolean writeFenced, @NotBlank String writeFenceNote, boolean allowTargetFlush,
            @NotBlank String confirmationTaskNo) {
    }
    public record FinishRequest(boolean sourceWriteFenced, @NotBlank String sourceFenceNote) {
    }
    public record SourceFenceRequest(@NotBlank String note) {
    }
    public record LimitRequest(@Positive long rateLimitOps, @Positive long bandwidthLimitBytesPerSecond,
            @Positive long spoolLimitBytes) {
    }
    public record TaskDetail(SyncTask task, List<SyncTaskEvent> events, SyncRuntime runtime,
            List<SyncChannelCheckpoint> channels, SyncPrecheckReport precheck,
            List<SyncMetricSnapshot> metrics) {
    }

    private ApiResponse<SyncTask> action(HttpServletRequest request, String key, String operation, Object body,
            java.util.function.Supplier<SyncTask> action) {
        String operator = operator(request);
        return wrap(idempotency.execute(operator, key, operation, body, action, x -> x.id().toString(),
                rid -> service.get(Long.parseLong(rid))), request);
    }
    private static String operator(HttpServletRequest request) {
        String x = request.getHeader("X-Operator");
        return x == null ? "anonymous" : x;
    }
    private static <T> ApiResponse<T> wrap(T value, HttpServletRequest request) {
        return ApiResponse.of(value, String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
