package io.github.susongyan.redisops.platform.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class GovernanceDatabaseErrorTest {
    @RestController
    static class FailingController {
        @GetMapping("/api/v1/ttl-governance-tasks/{id}")
        Object detail() {
            throw new BadSqlGrammarException("query", "private SQL", new SQLException("private detail"));
        }
    }

    @Test
    void databaseFailureReturnsSanitized500WithoutErrorDispatch() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/ttl-governance-tasks/1")
                .requestAttr(RequestIdFilter.ATTRIBUTE, "test-request"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DATABASE_ERROR"))
                .andExpect(jsonPath("$.requestId").value("test-request"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(
                        content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }
}
