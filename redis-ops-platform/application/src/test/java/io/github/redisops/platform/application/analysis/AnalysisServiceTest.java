package io.github.redisops.platform.application.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.redisops.platform.domain.analysis.AnalysisEvidence;
import io.github.redisops.platform.domain.analysis.AnalysisAgentClient;
import io.github.redisops.platform.domain.analysis.AnalysisProtocol;
import io.github.redisops.platform.domain.analysis.AnalysisRequest;
import io.github.redisops.platform.domain.analysis.AnalysisResult;
import io.github.redisops.platform.domain.analysis.AnalysisType;
import io.github.redisops.platform.domain.analysis.AnalysisRunRepository;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalysisServiceTest {
    @Test
    void persistenceFailureDoesNotInvokeAnotherProvider() {
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.save(any())).thenThrow(new IllegalStateException("storage unavailable"));
        AnalysisAgentClient fallback = mock(AnalysisAgentClient.class);
        var service = new AnalysisService(List.of(new RuleBasedAnalysisClient(), fallback), runs);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.analyze(
                new AnalysisRequest(AnalysisType.SYNC, "SYNC_TASK", "1", Map.of(), List.of(), List.of())));
        org.mockito.Mockito.verifyNoInteractions(fallback);
    }

    @Test
    void usesRuleProviderWhenNoExternalAgentIsConfigured() {
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AnalysisService service = new AnalysisService(List.of(new RuleBasedAnalysisClient()), runs);
        var result = service.analyze(new AnalysisRequest(AnalysisType.SYNC, "SYNC_TASK", "12",
                Map.of("rpoSeconds", "8"), List.of(new AnalysisEvidence("metric:rpo", "METRIC", "RPO 8s")),
                List.of("incident:42")));
        assertEquals("COMPLETED", result.status());
        assertEquals("RULE", result.protocol().name());
        assertTrue(result.recommendations().get(0).requiresApproval());
    }

    @Test
    void reportsRemoteFailureWithoutRuleFallback() {
        AnalysisAgentClient broken = new AnalysisAgentClient() {
            public String provider() {
                return "broken";
            }
            public AnalysisProtocol protocol() {
                return AnalysisProtocol.HTTP_JSON;
            }
            public java.util.Set<AnalysisType> supportedTypes() {
                return EnumSet.allOf(AnalysisType.class);
            }
            public AnalysisResult analyze(String id, AnalysisRequest request) {
                throw new IllegalStateException("unavailable");
            }
        };
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new AnalysisService(List.of(broken, new RuleBasedAnalysisClient()), runs);
        org.junit.jupiter.api.Assertions.assertThrows(io.github.redisops.platform.common.BusinessException.class,
                () -> service.analyze(
                        new AnalysisRequest(AnalysisType.ALERT, "ALERT", "1", Map.of(), List.of(), List.of())));
        org.mockito.Mockito.verifyNoInteractions(runs);
    }
}
