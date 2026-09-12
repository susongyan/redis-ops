package io.github.redisops.worker;

import io.github.redisops.application.alert.NotificationChannelService;
import io.github.redisops.domain.alert.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryWorker {
    private final NotificationDeliveryRepository deliveries;
    private final NotificationChannelService channels;
    private final AlertRepository alerts;
    private final WebhookDispatchPort webhook;
    public NotificationDeliveryWorker(NotificationDeliveryRepository deliveries, NotificationChannelService channels,
            AlertRepository alerts, WebhookDispatchPort webhook) {
        this.deliveries = deliveries;
        this.channels = channels;
        this.alerts = alerts;
        this.webhook = webhook;
    }
    @Scheduled(fixedDelayString = "${alert.notification.poll-interval-ms:5000}")
    public void deliver() {
        for (var delivery : deliveries.due(50)) {
            try {
                var channel = channels.encrypted(delivery.channelId());
                var event = alerts.findEvent(delivery.alertEventId()).orElseThrow();
                char[] url = channels.decrypt(channel);
                try {
                    webhook.dispatch(url, payload(event));
                    deliveries.markSent(delivery.id());
                } finally {
                    Arrays.fill(url, '\0');
                }
            } catch (RuntimeException failure) {
                int attempt = delivery.attemptCount() + 1;
                long seconds = attempt >= 8 ? 86400 : Math.min(3600, 1L << Math.min(attempt, 11));
                deliveries.retry(delivery.id(), attempt, Instant.now().plus(Duration.ofSeconds(seconds)),
                        attempt >= 8 ? "delivery failed after retry limit" : "delivery failed");
            }
        }
    }
    private static String payload(AlertEvent event) {
        return "{\"eventId\":" + event.id() + ",\"severity\":\"" + event.severity() + "\",\"status\":\""
                + event.status() + "\",\"title\":\"" + escape(event.title()) + "\",\"resourceType\":\""
                + event.resourceType() + "\",\"resourceId\":\"" + event.resourceId() + "\"}";
    }
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
