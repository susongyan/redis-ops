package io.github.susongyan.redisops.platform.api.history;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.github.susongyan.redisops.platform.api.GlobalExceptionHandler;
import io.github.susongyan.redisops.platform.application.IdempotencyService;
import io.github.susongyan.redisops.platform.application.history.ExecutionRecordService;
import io.github.susongyan.redisops.platform.domain.history.ExecutionRecordRepository.Type;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExecutionRecordApiTest {
    @Test
    void requiresConcurrencyHeadersRejectsModificationHistoriesAndReplaysDelete() throws Exception {
        var service = mock(ExecutionRecordService.class);
        var idempotency = mock(IdempotencyService.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(4)).run();
            return null;
        }).when(idempotency).executeVoid(anyString(), anyString(), anyString(), any(), any(), anyString());
        var mvc = MockMvcBuilders.standaloneSetup(new ExecutionRecordController(service, idempotency))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String path = "/api/v1/execution-records/RISK_SCAN/1";
        mvc.perform(delete(path).principal(() -> "user:7").header("If-Match", "2")).andExpect(status().isBadRequest());
        mvc.perform(delete(path).principal(() -> "user:7").header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/execution-records/TTL/1").principal(() -> "user:7").header("If-Match", "2")
                .header("Idempotency-Key", "key")).andExpect(status().isBadRequest());
        verifyNoInteractions(service, idempotency);
        mvc.perform(delete(path).principal(() -> "user:7").header("If-Match", "2").header("Idempotency-Key", "key"))
                .andExpect(status().isNoContent());
        verify(service).delete(Type.RISK_SCAN, 1, 2, "user:7");
    }
}
