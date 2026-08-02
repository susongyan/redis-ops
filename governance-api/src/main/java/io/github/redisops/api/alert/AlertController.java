package io.github.redisops.api.alert;
import io.github.redisops.api.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.application.alert.AlertService;
import io.github.redisops.common.PageResult;
import io.github.redisops.domain.alert.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
public class AlertController {
    private final AlertService s;
    private final IdempotencyService i;
    public AlertController(AlertService s, IdempotencyService i) {
        this.s = s;
        this.i = i;
    }
    @PostMapping("/api/v1/alert-rules")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AlertRule> create(@RequestHeader("Idempotency-Key") String k, @RequestBody Create b,
            HttpServletRequest r) {
        return w(i.execute(op(r), k, "ALERT_RULE_CREATE", b,
                () -> s.create(b.name, b.ruleType, b.severity, b.thresholdValue, b.durationSeconds, b.channelId),
                x -> x.id().toString(), x -> s.rule(Long.parseLong(x))), r);
    }
    @GetMapping("/api/v1/alert-rules")
    ApiResponse<List<AlertRule>> rules(HttpServletRequest r) {
        return w(s.rules(), r);
    }
    @PutMapping("/api/v1/alert-rules/{id}")
    ApiResponse<AlertRule> update(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, @RequestBody Update body, HttpServletRequest request) {
        return w(i.execute(op(request), key, "ALERT_RULE_UPDATE", body,
                () -> s.update(id, version, body.name, body.ruleType, body.severity, body.enabled,
                        body.thresholdValue, body.durationSeconds, body.channelId),
                x -> x.id().toString(), x -> s.rule(Long.parseLong(x))), request);
    }
    @GetMapping("/api/v1/alerts")
    ApiResponse<PageResult<AlertEvent>> events(@RequestParam(required = false) AlertStatus status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest r) {
        return w(s.events(status, page, size), r);
    }
    @PostMapping("/api/v1/alerts/{id}/acknowledge")
    ApiResponse<AlertEvent> ack(@PathVariable long id, @RequestHeader("Idempotency-Key") String k,
            @RequestHeader("If-Match") long v, HttpServletRequest r) {
        return w(i.execute(op(r), k, "ALERT_ACK", Map.of("id", id), () -> s.acknowledge(id, op(r), v),
                x -> x.id().toString(), x -> s.event(Long.parseLong(x))), r);
    }
    @PostMapping("/api/v1/alerts/{id}/resolve")
    ApiResponse<AlertEvent> resolve(@PathVariable long id, @RequestHeader("Idempotency-Key") String k,
            @RequestHeader("If-Match") long v, HttpServletRequest r) {
        return w(i.execute(op(r), k, "ALERT_RESOLVE", Map.of("id", id), () -> s.resolve(id, v), x -> x.id().toString(),
                x -> s.event(Long.parseLong(x))), r);
    }
    @PostMapping("/api/v1/alerts/{id}/silence")
    ApiResponse<AlertEvent> silence(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, @RequestBody Silence body, HttpServletRequest request) {
        return w(i.execute(op(request), key, "ALERT_SILENCE", body, () -> s.silence(id, body.until, version),
                x -> x.id().toString(), x -> s.event(Long.parseLong(x))), request);
    }
    record Create(@NotBlank String name, @NotBlank String ruleType, AlertSeverity severity, Double thresholdValue,
            @Min(0) Integer durationSeconds, Long channelId) {
    }
    record Update(@NotBlank String name, String ruleType, AlertSeverity severity, boolean enabled,
            Double thresholdValue,
            @Min(0) Integer durationSeconds, Long channelId) {
    }
    record Silence(@NotNull java.time.Instant until) {
    }
    private static String op(HttpServletRequest r) {
        String x = r.getHeader("X-Operator");
        return x == null ? "anonymous" : x;
    }
    private static <T> ApiResponse<T> w(T x, HttpServletRequest r) {
        return ApiResponse.of(x, String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
