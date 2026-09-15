package io.github.susongyan.redisops.platform.api.distribution;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.susongyan.redisops.platform.application.IdempotencyService;
import io.github.susongyan.redisops.platform.application.distribution.DistributionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DistributionApiTest {
    @Test
    void previewDoesNotPersistIdempotentPayloadAndForbidsCaching() throws Exception {
        var service = mock(DistributionService.class);
        var idem = mock(IdempotencyService.class);
        when(service.preview(1, 0)).thenReturn(new DistributionService.Preview(List.of(), 0, 0, 0, false, 1, 1));
        var mvc = MockMvcBuilders.standaloneSetup(new DistributionController(service, idem))
                .addFilters(new DistributionRequestLimitFilter()).build();
        mvc.perform(post("/api/v1/key-distributions/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clusterId\":1,\"database\":0}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(idem);
    }
    @Test
    void invalidRuleInputNeverEchoesJsonDiagnostics() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DistributionController(mock(DistributionService.class),
                mock(IdempotencyService.class))).addFilters(new DistributionRequestLimitFilter()).build();
        mvc.perform(post("/api/v1/key-distributions/tasks").header("Idempotency-Key", "test")
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"PRIVATE_RULE_MARKER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_RULE_MARKER"))));
        mvc.perform(post("/api/v1/key-distributions/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(65537))).andExpect(status().isPayloadTooLarge());
    }
    @Test
    void formalWritesRequireConcurrencyHeaders() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DistributionController(mock(DistributionService.class),
                mock(IdempotencyService.class))).build();
        mvc.perform(post("/api/v1/key-distributions/tasks/1/pause").header("Idempotency-Key", "test"))
                .andExpect(status().isBadRequest());
    }
}
