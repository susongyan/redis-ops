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
    void capabilityQueryUsesExplicitPolicyWithoutUpgradingLegacyRequests() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SyncController(null, null)).build();
        var json = new ObjectMapper();
        for (String version : Set.of("v1", "v2")) {
            var result = mvc.perform(get("/api/v1/sync-command-capabilities")
                    .param("targetMode", "CLUSTER").param("policyVersion", version))
                    .andExpect(status().isOk()).andReturn();
            var data = json.readTree(result.getResponse().getContentAsString()).path("data");
            assertEquals(version, data.path("policy").path("policyVersion").asText());
            boolean found = false;
            for (var command : data.path("commands"))
                if (command.path("command").asText().equals("BITOP")) {
                    assertEquals(version.equals("v2") ? "CONDITIONAL" : "HARD_BLOCKED",
                            command.path("category").asText());
                    found = true;
                }
            assertTrue(found);
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
