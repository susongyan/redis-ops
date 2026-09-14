package io.github.redisops.platform.api.distribution;

import io.github.redisops.platform.api.*;
import io.github.redisops.platform.application.IdempotencyService;
import io.github.redisops.platform.application.distribution.DistributionService;
import io.github.redisops.platform.domain.distribution.*;
import io.github.redisops.platform.common.PageResult;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestController
@RequestMapping("/api/v1/key-distributions")
public class DistributionController {
    private final DistributionService service;
    private final IdempotencyService idempotency;
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> invalidInput() {
        // Jackson diagnostics can contain submitted rule text. Never log or echo those diagnostics.
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_DISTRIBUTION_REQUEST", "message", "invalid distribution request"));
    }
    public DistributionController(DistributionService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }
    public record PreviewRequest(long clusterId, int database) {
    }
    @PostMapping("/preview")
    public ApiResponse<DistributionService.Preview> preview(@RequestBody PreviewRequest body, HttpServletRequest r,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return wrap(service.preview(body.clusterId(), body.database()), r);
    }
    @PostMapping("/tasks")
    public ApiResponse<DistributionTask> create(@RequestHeader("Idempotency-Key") String key,
            @RequestBody DistributionSpec body, HttpServletRequest r) {
        return wrap(idempotency.execute(operator(r), key, "DISTRIBUTION_CREATE", body, () -> service.create(body),
                t -> String.valueOf(t.id()), id -> service.get(Long.parseLong(id))), r);
    }
    @GetMapping("/tasks")
    public ApiResponse<PageResult<DistributionTask>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, HttpServletRequest r) {
        return wrap(service.list(page, size), r);
    }
    @GetMapping("/tasks/{id}")
    public ApiResponse<DistributionTask> get(@PathVariable long id, HttpServletRequest r) {
        return wrap(service.get(id), r);
    }
    @GetMapping("/tasks/{id}/groups")
    public ApiResponse<PageResult<DistributionCounter.Entry>> groups(@PathVariable long id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest r) {
        return wrap(service.groups(id, page, size), r);
    }
    @PostMapping("/tasks/{id}/{action:pause|resume|cancel}")
    public ApiResponse<DistributionTask> control(@PathVariable long id, @PathVariable String action,
            @RequestHeader("If-Match") long version, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest r) {
        return wrap(idempotency.execute(operator(r), key, "DISTRIBUTION_" + action,
                Map.of("id", id, "version", version), () -> service.control(id, version, action),
                t -> String.valueOf(t.id()), s -> service.get(Long.parseLong(s))), r);
    }
    private static String operator(HttpServletRequest r) {
        return r.getUserPrincipal().getName() == null ? "anonymous" : r.getUserPrincipal().getName();
    }
    private static <T> ApiResponse<T> wrap(T value, HttpServletRequest r) {
        return ApiResponse.of(value, String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
