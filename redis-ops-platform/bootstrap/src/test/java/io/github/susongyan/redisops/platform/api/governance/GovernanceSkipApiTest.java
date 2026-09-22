package io.github.susongyan.redisops.platform.api.governance;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.github.susongyan.redisops.platform.api.GlobalExceptionHandler;
import io.github.susongyan.redisops.platform.application.IdempotencyService;
import io.github.susongyan.redisops.platform.application.governance.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GovernanceSkipApiTest {
    @Test
    void bothEndpointsRequireExplicitConfirmationReasonAndConcurrencyHeaders() throws Exception {
        var ttl = mock(TtlGovernanceService.class);
        var cleanup = mock(CleanupGovernanceService.class);
        var idempotency = mock(IdempotencyService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TtlGovernanceController(ttl, idempotency),
                new CleanupGovernanceController(cleanup, idempotency)).setControllerAdvice(new GlobalExceptionHandler())
                .build();
        for (String type : new String[]{"ttl", "cleanup"}) {
            String url = "/api/v1/" + type + "-governance-tasks/1/skip-dry-run-and-start";
            for (String body : new String[]{"{}", "{\"reason\":\"CHG-1\"}",
                    "{\"reason\":\"CHG-1\",\"confirmed\":false}", "{\"reason\":\" \",\"confirmed\":true}"})
                mvc.perform(post(url).principal(() -> "user:7").header("If-Match", "0").header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            mvc.perform(post(url).principal(() -> "user:7").header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"CHG-1\",\"confirmed\":true}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(url).principal(() -> "user:7").header("If-Match", "0")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"CHG-1\",\"confirmed\":true}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(ttl, cleanup, idempotency);
    }
}
