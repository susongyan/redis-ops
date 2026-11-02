package io.github.susongyan.redisops.platform.infrastructure.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.domain.sync.WorkerLoad;
import io.github.susongyan.redisops.platform.domain.sync.WorkerLoadPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Pulls GET http://ip:port/actuator/workerload with short timeouts — this is a click-triggered
 * page read, so a hung worker must fail fast rather than hold a platform request thread.
 */
@Component
public class HttpWorkerLoadPort implements WorkerLoadPort {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public HttpWorkerLoadPort(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public WorkerLoad fetch(String workerIp, int workerPort) {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://" + workerIp + ":" + workerPort + "/actuator/workerload"))
                .timeout(Duration.ofSeconds(4)).GET().build();
        try {
            var response = client.send(request, BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException("worker responded HTTP " + response.statusCode());
            JsonNode node = mapper.readTree(response.body());
            return new WorkerLoad(text(node, "instanceName"), node.path("cpuSystemPercent").asDouble(-1),
                    node.path("cpuProcessPercent").asDouble(-1), node.path("heapUsedBytes").asLong(-1),
                    node.path("heapMaxBytes").asLong(-1), node.path("systemMemoryTotalBytes").asLong(-1),
                    node.path("systemMemoryFreeBytes").asLong(-1), text(node, "spoolDir"),
                    node.path("spoolDirBytes").asLong(-1), node.path("spoolUsableBytes").asLong(-1),
                    node.path("spoolTotalBytes").asLong(-1));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker load fetch interrupted", error);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("worker returned malformed load payload", error);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("worker unreachable: " + error.getMessage(), error);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
