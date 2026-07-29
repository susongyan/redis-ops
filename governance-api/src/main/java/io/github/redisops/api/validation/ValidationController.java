package io.github.redisops.api.validation;

import io.github.redisops.api.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.application.validation.ValidationService;
import io.github.redisops.common.PageResult;
import io.github.redisops.domain.validation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class ValidationController {
    private final ValidationService service;
    private final IdempotencyService idempotency;
    public ValidationController(ValidationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }
    @PostMapping("/api/v1/validation-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ValidationTask> create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateRequest body,
            HttpServletRequest request) {
        String operator = operator(request);
        return wrap(idempotency.execute(operator, key, "VALIDATION_TASK_CREATE", body,
                () -> service.create(body.syncTaskId, body.sourceClusterId, body.targetClusterId, body.sourceDb,
                        body.targetDb,
                        body.strictness, body.includePatterns, body.excludePatterns, body.samplingMode,
                        body.sampleLimit,
                        body.samplePercentage,
                        body.ttlToleranceSeconds,
                        body.largeKeyThresholdBytes, body.maxDeepCompareBytes, body.chunkBytes, body.maxElementsPerKey),
                x -> x.id().toString(), rid -> service.get(Long.parseLong(rid))), request);
    }
    @GetMapping("/api/v1/validation-tasks")
    ApiResponse<List<ValidationTask>> list(HttpServletRequest request) {
        return wrap(service.list(), request);
    }
    @GetMapping("/api/v1/validation-tasks/{id}")
    ApiResponse<Detail> get(@PathVariable long id, HttpServletRequest request) {
        return wrap(new Detail(service.get(id), service.latestRun(id).orElse(null)), request);
    }
    @PostMapping("/api/v1/validation-tasks/{id}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<ValidationTask> start(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        String op = operator(request);
        return wrap(idempotency.execute(op, key, "VALIDATION_TASK_START", Map.of("id", id),
                () -> service.start(id, version, key), x -> x.id().toString(), rid -> service.get(Long.parseLong(rid))),
                request);
    }
    @PostMapping("/api/v1/validation-tasks/{id}/cancel")
    ApiResponse<ValidationTask> cancel(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, HttpServletRequest request) {
        String op = operator(request);
        return wrap(idempotency.execute(op, key, "VALIDATION_TASK_CANCEL", Map.of("id", id),
                () -> service.cancel(id, version), x -> x.id().toString(), rid -> service.get(Long.parseLong(rid))),
                request);
    }
    @GetMapping("/api/v1/validation-tasks/{id}/differences")
    ApiResponse<PageResult<ValidationDifference>> differences(@PathVariable long id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return wrap(service.differences(id, page, size), request);
    }
    public record CreateRequest(Long syncTaskId, @Positive long sourceClusterId, @Positive long targetClusterId,
            @Min(0) Integer sourceDb, @Min(0) Integer targetDb, ValidationStrictness strictness,
            @Size(max = 100) List<String> includePatterns, @Size(max = 100) List<String> excludePatterns,
            ValidationSamplingMode samplingMode,
            @Min(1) @Max(1000000) Integer sampleLimit, @Min(0) @Max(3600) Long ttlToleranceSeconds,
            @DecimalMin(value = "0.01") @DecimalMax(value = "100.00") Double samplePercentage,
            @Min(1) Long largeKeyThresholdBytes, @Min(1) Long maxDeepCompareBytes,
            @Min(1024) @Max(8388608) Integer chunkBytes, @Min(1) @Max(1000000) Integer maxElementsPerKey) {
    }
    public record Detail(ValidationTask task, ValidationRun latestRun) {
    }
    private static String operator(HttpServletRequest request) {
        String value = request.getHeader("X-Operator");
        return value == null ? "anonymous" : value;
    }
    private static <T> ApiResponse<T> wrap(T value, HttpServletRequest request) {
        return ApiResponse.of(value, String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
