package io.github.susongyan.redisops.platform.infrastructure.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisAgentClient;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisEvidence;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisProtocol;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisRecommendation;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisRequest;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisResult;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Optional model/agent adapter. An empty endpoint keeps the rule provider as the fallback. */
@Component
@Order(10)
public class HttpJsonAnalysisAgentClient implements AnalysisAgentClient {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String endpoint;
    private final Duration timeout;
    private final Set<AnalysisType> supportedTypes;

    public HttpJsonAnalysisAgentClient(ObjectMapper mapper,
            @Value("${redis-ops.analysis.http.endpoint:}") String endpoint,
            @Value("${redis-ops.analysis.http.timeout-ms:5000}") long timeoutMs,
            @Value("${redis-ops.analysis.http.types:ALERT,SYNC,VALIDATION,RISK_SCAN,INCIDENT}") String types) {
        this.mapper = mapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        if (timeoutMs < 100 || timeoutMs > 120000)
            throw new IllegalArgumentException("analysis HTTP timeout must be between 100 and 120000 ms");
        this.timeout = Duration.ofMillis(timeoutMs);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.supportedTypes = parseTypes(types, this.endpoint);
    }

    @Override
    public String provider() {
        return "http-json-agent";
    }

    @Override
    public AnalysisProtocol protocol() {
        return AnalysisProtocol.HTTP_JSON;
    }

    @Override
    public Set<AnalysisType> supportedTypes() {
        return supportedTypes;
    }

    @Override
    public AnalysisResult analyze(String requestId, AnalysisRequest request) {
        if (endpoint.isBlank())
            throw new IllegalStateException("HTTP analysis endpoint is not configured");
        try {
            String body = mapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .header("X-Analysis-Request-Id", requestId).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(httpRequest, AnalysisResponseBody.handler());
            return parseResult(requestId, AnalysisResponseBody.read(response), request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("analysis agent request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("analysis agent request failed", e);
        }
    }

    private AnalysisResult parseResult(String requestId, String body, AnalysisRequest request) throws Exception {
        JsonNode root = mapper.readTree(body);
        if (root == null || !root.isObject() || !root.path("summary").isTextual()
                || !root.path("confidence").isNumber())
            throw new IllegalArgumentException("invalid analysis response");
        String summary = text(root, "summary", "External analysis completed");
        double confidence = root.path("confidence").isNumber() ? root.path("confidence").asDouble() : 0.0d;
        List<AnalysisEvidence> evidence = new ArrayList<>(request.evidence());
        for (JsonNode item : root.path("evidenceRefs")) {
            String reference = item.isTextual() ? item.asText() : text(item, "reference", "");
            if (!reference.isBlank())
                evidence.add(new AnalysisEvidence(reference, "AGENT", null));
        }
        List<AnalysisRecommendation> recommendations = new ArrayList<>();
        for (JsonNode item : root.path("recommendations")) {
            String action = text(item, "action", "REVIEW_EVIDENCE");
            recommendations.add(new AnalysisRecommendation(action, text(item, "reason", ""),
                    item.path("requiresApproval").asBoolean(true)));
        }
        return new AnalysisResult(requestId, protocol(), provider(), "COMPLETED", summary,
                Math.max(0, Math.min(1, confidence)), evidence, recommendations, Instant.now());
    }

    private static Set<AnalysisType> parseTypes(String raw, String endpoint) {
        if (endpoint.isBlank())
            return Set.of();
        EnumSet<AnalysisType> result = EnumSet.noneOf(AnalysisType.class);
        for (String value : raw.split(",")) {
            try {
                result.add(AnalysisType.valueOf(value.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // Invalid configuration is ignored; the rule provider remains available.
            }
        }
        return Set.copyOf(result);
    }

    private static String text(JsonNode node, String name, String fallback) {
        JsonNode value = node.path(name);
        return value.isTextual() ? value.asText() : fallback;
    }
}
