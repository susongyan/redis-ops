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

/** ACP REST adapter. Manifest and run paths are configurable because ACP deployments may expose different bases. */
@Component
@Order(20)
public class AcpAnalysisAgentClient implements AnalysisAgentClient {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String endpoint;
    private final String manifestPath;
    private final String runPath;
    private final Set<AnalysisType> supportedTypes;
    private final Duration timeout;

    public AcpAnalysisAgentClient(ObjectMapper mapper,
            @Value("${redis-ops.analysis.acp.endpoint:}") String endpoint,
            @Value("${redis-ops.analysis.acp.manifest-path:/manifest}") String manifestPath,
            @Value("${redis-ops.analysis.acp.run-path:/runs}") String runPath,
            @Value("${redis-ops.analysis.acp.timeout-ms:10000}") long timeoutMs,
            @Value("${redis-ops.analysis.acp.types:ALERT,SYNC,VALIDATION,RISK_SCAN,INCIDENT}") String types) {
        this.mapper = mapper;
        this.endpoint = trimTrailingSlash(endpoint);
        this.manifestPath = normalizePath(manifestPath, "/manifest");
        this.runPath = normalizePath(runPath, "/runs");
        this.timeout = Duration.ofMillis(Math.max(100, Math.min(timeoutMs, 120_000)));
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.supportedTypes = parseTypes(types, this.endpoint);
    }

    @Override
    public String provider() {
        return "acp-agent";
    }

    @Override
    public AnalysisProtocol protocol() {
        return AnalysisProtocol.ACP;
    }

    @Override
    public Set<AnalysisType> supportedTypes() {
        return supportedTypes;
    }

    @Override
    public AnalysisResult analyze(String requestId, AnalysisRequest request) {
        if (endpoint.isBlank())
            throw new IllegalStateException("ACP endpoint is not configured");
        try {
            JsonNode manifest = get(endpoint + manifestPath);
            if (!supports(manifest, request.type()))
                throw new IllegalStateException("ACP agent does not advertise analysis type " + request.type());
            String body = mapper.writeValueAsString(new RunPayload(requestId, request));
            JsonNode result = post(endpoint + runPath, requestId, body);
            return parseResult(requestId, result, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ACP agent request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("ACP agent request failed", e);
        }
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Accept", "application/json")
                .GET().build();
        var response = client.send(request, AnalysisResponseBody.handler());
        return mapper.readTree(AnalysisResponseBody.read(response));
    }

    private JsonNode post(String url, String requestId, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Content-Type", "application/json").header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, AnalysisResponseBody.handler());
        return mapper.readTree(AnalysisResponseBody.read(response));
    }

    private static boolean supports(JsonNode manifest, AnalysisType type) {
        JsonNode capabilities = manifest.path("capabilities");
        if (!capabilities.isArray() || capabilities.isEmpty())
            capabilities = manifest.path("metadata").path("capabilities");
        if (!capabilities.isArray() || capabilities.isEmpty())
            return false;
        for (JsonNode item : capabilities) {
            String value = item.isTextual() ? item.asText() : item.path("name").asText("");
            if (value.equalsIgnoreCase(type.name()) || value.equalsIgnoreCase("ANALYSIS"))
                return true;
        }
        return false;
    }

    private AnalysisResult parseResult(String requestId, JsonNode root, AnalysisRequest request) {
        if (root == null || !root.isObject())
            throw new IllegalArgumentException("invalid ACP response");
        JsonNode result = root.has("result") ? root.path("result") : root;
        if (!result.isObject() || !result.path("summary").isTextual() || !result.path("confidence").isNumber())
            throw new IllegalArgumentException("invalid ACP result");
        String status = result.path("status").asText("COMPLETED");
        String summary = result.path("summary").asText(result.path("message").asText("ACP analysis completed"));
        double confidence = result.path("confidence").isNumber() ? result.path("confidence").asDouble() : 0.0d;
        List<AnalysisEvidence> evidence = new ArrayList<>(request.evidence());
        for (JsonNode item : result.path("evidenceRefs")) {
            String reference = item.isTextual() ? item.asText() : item.path("reference").asText("");
            if (!reference.isBlank())
                evidence.add(new AnalysisEvidence(reference, "ACP", null));
        }
        List<AnalysisRecommendation> recommendations = new ArrayList<>();
        for (JsonNode item : result.path("recommendations"))
            recommendations.add(new AnalysisRecommendation(item.path("action").asText("REVIEW_EVIDENCE"),
                    item.path("reason").asText(""), item.path("requiresApproval").asBoolean(true)));
        return new AnalysisResult(requestId, protocol(), provider(), status, summary,
                Math.max(0, Math.min(1, confidence)),
                evidence, recommendations, Instant.now());
    }

    private record RunPayload(String requestId, AnalysisRequest request) {
    }

    private static Set<AnalysisType> parseTypes(String raw, String endpoint) {
        if (endpoint.isBlank())
            return Set.of();
        EnumSet<AnalysisType> result = EnumSet.noneOf(AnalysisType.class);
        for (String value : raw.split(",")) {
            try {
                result.add(AnalysisType.valueOf(value.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // Invalid configuration is ignored and the rule provider remains available.
            }
        }
        return Set.copyOf(result);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank())
            return "";
        return value.replaceAll("/+$", "");
    }

    private static String normalizePath(String value, String fallback) {
        if (value == null || value.isBlank())
            return fallback;
        return value.startsWith("/") ? value : "/" + value;
    }
}
