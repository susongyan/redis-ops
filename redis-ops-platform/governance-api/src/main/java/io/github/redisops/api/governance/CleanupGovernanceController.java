package io.github.redisops.api.governance;

import io.github.redisops.api.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.application.governance.CleanupGovernanceService;
import io.github.redisops.domain.governance.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class CleanupGovernanceController {
    private final CleanupGovernanceService service;
    private final IdempotencyService idempotency;
    public CleanupGovernanceController(CleanupGovernanceService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }
    @PostMapping("/api/v1/cleanup-governance-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<CleanupGovernanceTask> create(@RequestHeader("Idempotency-Key") String key, @RequestBody Create body,
            HttpServletRequest request) {
        return response(idempotency.execute(operator(request), key, "CLEANUP_GOVERNANCE_CREATE", body,
                () -> service.create(body.clusterId, body.databaseNo, body.includePattern, body.impactLimit,
                        body.scanRatePerSecond == null ? 200 : body.scanRatePerSecond, operator(request)),
                x -> x.id().toString(), x -> service.get(Long.parseLong(x))), request);
    }
    @GetMapping("/api/v1/cleanup-governance-tasks")
    ApiResponse<List<CleanupGovernanceTask>> list(HttpServletRequest request) {
        return response(service.list(), request);
    }
    @GetMapping("/api/v1/cleanup-governance-tasks/{id}")
    ApiResponse<Detail> get(@PathVariable long id, HttpServletRequest request) {
        return response(new Detail(service.get(id), service.latest(id).orElse(null), service.checkpoints(id)), request);
    }
    @PostMapping("/api/v1/cleanup-governance-tasks/{id}/dry-run")
    ApiResponse<CleanupGovernanceTask> dryRun(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return command(id, key, version, "CLEANUP_GOVERNANCE_DRY_RUN",
                () -> service.dryRun(id, version, operator(request), key), request);
    }
    @PostMapping("/api/v1/cleanup-governance-tasks/{id}/approve")
    ApiResponse<CleanupGovernanceTask> approve(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, @RequestBody Approval body, HttpServletRequest request) {
        return command(id, key, version, "CLEANUP_GOVERNANCE_APPROVE",
                () -> service.approve(id, version, operator(request), body.note), request);
    }
    @PostMapping("/api/v1/cleanup-governance-tasks/{id}/start")
    ApiResponse<CleanupGovernanceTask> start(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return command(id, key, version, "CLEANUP_GOVERNANCE_START",
                () -> service.start(id, version, operator(request), key), request);
    }
    @PostMapping("/api/v1/cleanup-governance-tasks/{id}/pause")
    ApiResponse<CleanupGovernanceTask> pause(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return command(id, key, version, "CLEANUP_GOVERNANCE_PAUSE",
                () -> service.pause(id, version, operator(request)), request);
    }
    @PostMapping("/api/v1/cleanup-governance-tasks/{id}/cancel")
    ApiResponse<CleanupGovernanceTask> cancel(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return command(id, key, version, "CLEANUP_GOVERNANCE_CANCEL",
                () -> service.cancel(id, version, operator(request)), request);
    }
    private ApiResponse<CleanupGovernanceTask> command(long id, String key, long version, String op,
            java.util.function.Supplier<CleanupGovernanceTask> action, HttpServletRequest request) {
        return response(idempotency.execute(operator(request), key, op, Map.of("id", id, "version", version), action,
                x -> x.id().toString(), x -> service.get(Long.parseLong(x))), request);
    }
    record Create(@Positive long clusterId, @Min(0) Integer databaseNo, String includePattern,
            @Min(1) @Max(10000000) long impactLimit, @Min(1) @Max(100000) Integer scanRatePerSecond) {
    }
    record Approval(@NotBlank String note) {
    }
    record Detail(CleanupGovernanceTask task, CleanupGovernanceRun latestRun,
            List<CleanupGovernanceCheckpoint> checkpoints) {
    }
    private static String operator(HttpServletRequest request) {
        String value = request.getHeader("X-Operator");
        return value == null ? "anonymous" : value;
    }
    private static <T> ApiResponse<T> response(T data, HttpServletRequest request) {
        return ApiResponse.of(data, String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
