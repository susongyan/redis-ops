package io.github.redisops.api.governance;

import io.github.redisops.api.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.application.governance.TtlGovernanceService;
import io.github.redisops.domain.governance.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class TtlGovernanceController {
    private final TtlGovernanceService service;
    private final IdempotencyService idempotency;
    public TtlGovernanceController(TtlGovernanceService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }
    @PostMapping("/api/v1/ttl-governance-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<TtlGovernanceTask> create(@RequestHeader("Idempotency-Key") String key, @RequestBody Create body,
            HttpServletRequest request) {
        return response(idempotency.execute(operator(request), key, "TTL_GOVERNANCE_CREATE", body,
                () -> service.create(body.clusterId, body.databaseNo, body.includePattern, body.targetTtlSeconds,
                        body.scanRatePerSecond == null ? 500 : body.scanRatePerSecond,
                        body.maxKeys == null ? 100000 : body.maxKeys, operator(request)),
                x -> x.id().toString(), x -> service.get(Long.parseLong(x))), request);
    }
    @GetMapping("/api/v1/ttl-governance-tasks")
    ApiResponse<List<TtlGovernanceTask>> list(HttpServletRequest request) {
        return response(service.list(), request);
    }
    @GetMapping("/api/v1/ttl-governance-tasks/{id}")
    ApiResponse<Detail> get(@PathVariable long id, HttpServletRequest request) {
        return response(new Detail(service.get(id), service.latest(id).orElse(null), service.checkpoints(id)), request);
    }
    @PostMapping("/api/v1/ttl-governance-tasks/{id}/dry-run")
    ApiResponse<TtlGovernanceTask> dryRun(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return command(id, key, version, "TTL_GOVERNANCE_DRY_RUN",
                () -> service.dryRun(id, version, operator(request), key), request);
    }
    @PostMapping("/api/v1/ttl-governance-tasks/{id}/approve")
    ApiResponse<TtlGovernanceTask> approve(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return command(id, key, version, "TTL_GOVERNANCE_APPROVE",
                () -> service.approve(id, version, operator(request)), request);
    }
    @PostMapping("/api/v1/ttl-governance-tasks/{id}/start")
    ApiResponse<TtlGovernanceTask> start(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return command(id, key, version, "TTL_GOVERNANCE_START",
                () -> service.start(id, version, operator(request), key), request);
    }
    @PostMapping("/api/v1/ttl-governance-tasks/{id}/pause")
    ApiResponse<TtlGovernanceTask> pause(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return command(id, key, version, "TTL_GOVERNANCE_PAUSE", () -> service.pause(id, version, operator(request)),
                request);
    }
    @PostMapping("/api/v1/ttl-governance-tasks/{id}/cancel")
    ApiResponse<TtlGovernanceTask> cancel(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        return command(id, key, version, "TTL_GOVERNANCE_CANCEL", () -> service.cancel(id, version, operator(request)),
                request);
    }
    private ApiResponse<TtlGovernanceTask> command(long id, String key, long version, String operation,
            java.util.function.Supplier<TtlGovernanceTask> action, HttpServletRequest request) {
        return response(
                idempotency.execute(operator(request), key, operation, Map.of("id", id, "version", version), action,
                        x -> x.id().toString(), x -> service.get(Long.parseLong(x))),
                request);
    }
    record Create(@Positive long clusterId, @Min(0) Integer databaseNo, String includePattern,
            @Min(1) long targetTtlSeconds, @Min(1) @Max(100000) Integer scanRatePerSecond,
            @Min(1) @Max(10000000) Long maxKeys) {
    }
    record Detail(TtlGovernanceTask task, TtlGovernanceRun latestRun, List<TtlGovernanceCheckpoint> checkpoints) {
    }
    private static String operator(HttpServletRequest request) {
        String value = request.getHeader("X-Operator");
        return value == null ? "anonymous" : value;
    }
    private static <T> ApiResponse<T> response(T data, HttpServletRequest request) {
        return ApiResponse.of(data, String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
