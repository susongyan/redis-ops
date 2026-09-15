package io.github.susongyan.redisops.platform.domain.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisContractTest {
    @Test
    void recommendationsCannotAuthorizeProductionActions() {
        assertTrue(new AnalysisRecommendation("CHECK_SYNC_HEALTH", "review", false).requiresApproval());
    }
    @Test
    void invalidResultsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisResult("r", AnalysisProtocol.HTTP_JSON,
                "http", "COMPLETED", "summary", Double.NaN, List.of(), List.of(), Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisResult("r", AnalysisProtocol.ACP,
                "acp", "RUNNING", "summary", 0.5, List.of(), List.of(), Instant.now()));
    }
    @Test
    void factsHaveStableOrderingAndRejectSecretFields() {
        var left = new AnalysisRequest(AnalysisType.SYNC, "SYNC_TASK", "1", Map.of("b", "2", "a", "1"), null, null);
        var right = new AnalysisRequest(AnalysisType.SYNC, "SYNC_TASK", "1", Map.of("a", "1", "b", "2"), null, null);
        assertEquals(left.toString(), right.toString());
        assertThrows(IllegalArgumentException.class, () -> new AnalysisRequest(AnalysisType.SYNC, "SYNC_TASK", "1",
                Map.of("password", "not-allowed"), null, null));
    }
    @Test
    void registrationRejectsCredentialUrlsAndMalformedCapabilities() {
        assertThrows(IllegalArgumentException.class, () -> profile("https://user:password@localhost", "[]"));
        assertThrows(IllegalArgumentException.class, () -> profile("https://localhost", "{}"));
        assertDoesNotThrow(() -> profile("https://localhost/analyze", "[\"SYNC\", \"ALERT\"]"));
    }
    private AnalysisAgentProfile profile(String endpoint, String types) {
        return new AnalysisAgentProfile(null, "agent", AnalysisProtocol.HTTP_JSON, endpoint, false, types, 1000,
                100, 0, Instant.now(), Instant.now());
    }
}
