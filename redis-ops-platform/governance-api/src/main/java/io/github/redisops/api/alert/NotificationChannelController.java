package io.github.redisops.api.alert;

import io.github.redisops.api.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.application.alert.NotificationChannelService;
import io.github.redisops.domain.alert.NotificationChannel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationChannelController {
    private final NotificationChannelService channels;
    private final IdempotencyService idempotency;
    public NotificationChannelController(NotificationChannelService channels, IdempotencyService idempotency) {
        this.channels = channels;
        this.idempotency = idempotency;
    }
    @PostMapping("/api/v1/notification-channels")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<NotificationChannel> create(@RequestHeader("Idempotency-Key") String key, @RequestBody Create body,
            HttpServletRequest request) {
        return response(
                idempotency.execute(operator(request), key, "NOTIFICATION_CHANNEL_CREATE", body,
                        () -> channels.create(body.name, body.webhookUrl), x -> x.id().toString(), x -> channels.list()
                                .stream().filter(c -> c.id().equals(Long.parseLong(x))).findFirst().orElseThrow()),
                request);
    }
    @GetMapping("/api/v1/notification-channels")
    ApiResponse<List<NotificationChannel>> list(HttpServletRequest request) {
        return response(channels.list(), request);
    }
    @PutMapping("/api/v1/notification-channels/{id}")
    ApiResponse<NotificationChannel> update(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, @RequestBody Update body, HttpServletRequest request) {
        return response(idempotency.execute(operator(request), key, "NOTIFICATION_CHANNEL_UPDATE", body,
                () -> channels.update(id, version, body.name, body.webhookUrl, body.status),
                x -> x.id().toString(), x -> channels.list().stream()
                        .filter(c -> c.id().equals(Long.parseLong(x))).findFirst().orElseThrow()),
                request);
    }
    @GetMapping("/api/v1/notification-deliveries")
    ApiResponse<io.github.redisops.common.PageResult<io.github.redisops.domain.alert.NotificationDelivery>> history(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return response(channels.history(page, size), request);
    }
    record Create(@NotBlank String name, @NotBlank String webhookUrl) {
    }
    record Update(@NotBlank String name, String webhookUrl, String status) {
    }
    private static String operator(HttpServletRequest request) {
        String value = request.getHeader("X-Operator");
        return value == null ? "anonymous" : value;
    }
    private static <T> ApiResponse<T> response(T data, HttpServletRequest request) {
        return ApiResponse.of(data, String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
