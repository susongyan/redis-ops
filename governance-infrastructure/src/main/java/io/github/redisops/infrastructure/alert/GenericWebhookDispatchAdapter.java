package io.github.redisops.infrastructure.alert;

import io.github.redisops.domain.alert.WebhookDispatchPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class GenericWebhookDispatchAdapter implements WebhookDispatchPort {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Override
    public void dispatch(char[] url, String body) {
        try {
            var request = HttpRequest.newBuilder(URI.create(new String(url))).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300)
                throw new IllegalStateException("webhook returned non-success status");
        } catch (Exception exception) {
            throw new IllegalStateException("webhook delivery failed", exception);
        }
    }
}
