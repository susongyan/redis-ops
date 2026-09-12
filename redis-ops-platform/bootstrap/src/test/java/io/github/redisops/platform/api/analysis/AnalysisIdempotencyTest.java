package io.github.redisops.platform.api.analysis;

import io.github.redisops.platform.application.IdempotencyService;
import io.github.redisops.platform.application.analysis.AnalysisService;
import io.github.redisops.platform.application.analysis.RuleBasedAnalysisClient;
import io.github.redisops.platform.domain.analysis.*;
import io.github.redisops.platform.domain.idempotency.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisIdempotencyTest {
    @Test
    void sameKeyReplaysStoredResultWithoutCallingProviderAgain() {
        IdempotencyRepository records = mock(IdempotencyRepository.class);
        AtomicReference<String> digest = new AtomicReference<>();
        when(records.tryStart(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if (digest.get() != null)
                return false;
            digest.set(invocation.getArgument(3));
            return true;
        });
        when(records.find("anonymous", "same-key")).thenAnswer(invocation -> Optional.of(
                new IdempotencyRecord("anonymous", "same-key", "ANALYSIS_CREATE", digest.get(), "COMPLETED", "7")));
        AnalysisService service = mock(AnalysisService.class);
        AnalysisRequest input = new AnalysisRequest(AnalysisType.SYNC, "SYNC_TASK", "1", Map.of(), List.of(),
                List.of());
        AnalysisRun saved = new AnalysisRun(7L, input, new RuleBasedAnalysisClient().analyze("r1", input));
        when(service.analyzeRun(any())).thenReturn(saved);
        when(service.get(7)).thenReturn(saved);
        AnalysisController controller = new AnalysisController(service, new IdempotencyService(records));
        var request = new AnalysisController.Request(AnalysisType.SYNC, "SYNC_TASK", "1", Map.of(), List.of(),
                List.of());
        var http = new MockHttpServletRequest();
        assertEquals(controller.analyze("same-key", request, http).data(),
                controller.analyze("same-key", request, http).data());
        verify(service, times(1)).analyzeRun(any());
        assertThrows(RuntimeException.class, () -> controller.analyze("same-key",
                new AnalysisController.Request(AnalysisType.SYNC, "SYNC_TASK", "2", Map.of(), List.of(), List.of()),
                http));
    }
}
