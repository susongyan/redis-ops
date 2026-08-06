package io.github.redisops.api.operation;

import io.github.redisops.api.*;
import io.github.redisops.application.operation.RedisOperationService;
import io.github.redisops.application.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class RedisOperationController {
    private final RedisOperationService service;
    private final IdempotencyService idempotency;
    public RedisOperationController(RedisOperationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }
    @GetMapping("/api/v1/operation-commands")
    ApiResponse<?> commands(@RequestParam(defaultValue = "false") boolean writes,
            @RequestParam(defaultValue = "false") boolean includeDisabled, HttpServletRequest r) {
        return response(service.commands(writes, includeDisabled), r);
    }
    @PutMapping("/api/v1/operation-commands/{id}")
    ApiResponse<?> updateCommand(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, @RequestBody CommandUpdate b, HttpServletRequest r) {
        var operator = operator(r);
        var result = idempotency.execute(operator, key, "OPERATION_COMMAND_UPDATE", b,
                () -> service.updateCommand(id, version, b.enabled, b.riskLevel, b.approvalPolicy, b.maxValueBytes,
                        b.allowedDataTypes, b.missingKeyPolicy, b.blockedByDefault, b.changeReason, operator),
                x -> Long.toString(x.id()), resource -> service.commands(true, true).stream()
                        .filter(x -> x.id().equals(Long.valueOf(resource))).findFirst().orElseThrow());
        return response(result, r);
    }
    @PostMapping("/api/v1/redis-operations/preview")
    ApiResponse<?> preview(@RequestBody Request b, HttpServletRequest r) {
        return response(service.preview(b.clusterId, b.databaseNo, b.commandName, b.arguments), r);
    }
    @PostMapping("/api/v1/redis-operations")
    ApiResponse<?> create(@RequestHeader("Idempotency-Key") String key, @RequestBody Request b, HttpServletRequest r) {
        return response(service.request(b.clusterId, b.databaseNo, b.commandName, b.arguments, operator(r)), r);
    }
    @PostMapping("/api/v1/redis-operations/{id}/confirm")
    ApiResponse<?> confirm(@PathVariable long id, @RequestHeader("If-Match") long v, HttpServletRequest r) {
        return response(service.confirm(id, v, operator(r)), r);
    }
    @PostMapping("/api/v1/redis-operations/{id}/approve")
    ApiResponse<?> approve(@PathVariable long id, @RequestHeader("If-Match") long v, @RequestBody Note b,
            HttpServletRequest r) {
        return response(service.approve(id, v, operator(r), b.note), r);
    }
    @PostMapping("/api/v1/redis-operations/{id}/execute")
    ApiResponse<?> execute(@PathVariable long id, @RequestHeader("If-Match") long v, @RequestBody Request b,
            HttpServletRequest r) {
        return response(service.execute(id, v, operator(r), b.arguments), r);
    }
    @PostMapping("/api/v1/redis-operations/{id}/cancel")
    ApiResponse<?> cancel(@PathVariable long id, @RequestHeader("If-Match") long v, HttpServletRequest r) {
        return response(service.cancel(id, v, operator(r)), r);
    }
    @GetMapping("/api/v1/redis-operations/{id}")
    ApiResponse<?> get(@PathVariable long id, HttpServletRequest r) {
        return response(service.get(id), r);
    }
    @GetMapping("/api/v1/redis-operations")
    ApiResponse<?> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest r) {
        return response(service.list(page, size), r);
    }
    record Request(long clusterId, int databaseNo, @NotBlank String commandName, List<String> arguments) {
        Request {
            if (arguments == null)
                arguments = List.of();
        }
    }
    record Note(String note) {
    }
    record CommandUpdate(boolean enabled, String riskLevel, String approvalPolicy, int maxValueBytes,
            List<String> allowedDataTypes, String missingKeyPolicy, boolean blockedByDefault, String changeReason) {
    }
    private static String operator(HttpServletRequest r) {
        var x = r.getHeader("X-Operator");
        return x == null ? "anonymous" : x;
    }
    private static <T> ApiResponse<T> response(T x, HttpServletRequest r) {
        return ApiResponse.of(x, String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
