package io.github.susongyan.redisops.platform.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.api.sync.SyncController;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SyncCommandCapabilitiesHttpTest {
    @Test
    void scopeConfirmationAcceptsExplicitConsentOrRestrictedIncludes() throws Exception {
        var json = new ObjectMapper();
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            for (String body : new String[]{"{\"confirmFullKeyspace\":true}",
                    "{\"includePatterns\":[\"***\"],\"confirmFullKeyspace\":true}",
                    "{\"includePatterns\":[\"biz:*\"]}"}) {
                var request = json.readValue(body, SyncController.SyncTaskRequest.class);
                assertTrue(factory.getValidator().validate(request).isEmpty());
            }
            var unconfirmed = json.readValue("{\"includePatterns\":[\"***\"]}", SyncController.SyncTaskRequest.class);
            assertFalse(factory.getValidator().validate(unconfirmed).isEmpty());
        }
    }
    @Test
    void fullScopeCreationRequiresExplicitConfirmationBeforeInvokingServices() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SyncController(null, null)).build();
        for (String body : new String[]{"{}", "{\"includePatterns\":[\"*\"]}", "{\"includePatterns\":[]}"})
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/sync-tasks")
                    .header("Idempotency-Key", "scope-test").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
    }

    @Test
    void capabilityQueryUsesExplicitPolicyWithoutUpgradingLegacyRequests() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SyncController(null, null)).build();
        var json = new ObjectMapper();
        for (String version : Set.of("v1", "v2", "v3")) {
            var result = mvc.perform(get("/api/v1/sync-command-capabilities")
                    .param("targetMode", "CLUSTER").param("policyVersion", version))
                    .andExpect(status().isOk()).andReturn();
            var data = json.readTree(result.getResponse().getContentAsString()).path("data");
            assertEquals(version, data.path("policy").path("policyVersion").asText());
            boolean found = false;
            for (var command : data.path("commands"))
                if (command.path("command").asText().equals("BITOP")) {
                    assertEquals(version.equals("v1") ? "HARD_BLOCKED" : "CONDITIONAL",
                            command.path("category").asText());
                    found = true;
                }
            assertTrue(found);
            for (var command : data.path("commands"))
                if (command.path("command").asText().equals("MULTI"))
                    assertEquals(!version.equals("v3"), command.path("currentlyBlocked").asBoolean());
        }
    }
    @Test
    void httpCapabilitiesDoNotAdvertiseDestructiveAdmissionForLegacyAllowFlag() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SyncController(null, null)).build();
        var json = new ObjectMapper();
        for (String mode : Set.of("STANDALONE", "SENTINEL", "CLUSTER")) {
            var result = mvc.perform(get("/api/v1/sync-command-capabilities")
                    .param("targetMode", mode).param("allowDestructiveCommands", "true"))
                    .andExpect(status().isOk()).andReturn();
            var commands = json.readTree(result.getResponse().getContentAsString()).path("data").path("commands");
            int checked = 0;
            for (var command : commands) {
                if (!Set.of("FLUSHDB", "FLUSHALL").contains(command.path("command").asText()))
                    continue;
                assertEquals("HARD_BLOCKED", command.path("category").asText());
                assertTrue(command.path("currentlyBlocked").asBoolean());
                assertFalse(command.path("configurable").asBoolean());
                checked++;
            }
            assertEquals(2, checked);
        }
    }
}
