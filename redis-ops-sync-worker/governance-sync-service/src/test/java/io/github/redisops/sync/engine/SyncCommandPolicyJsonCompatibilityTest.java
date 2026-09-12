package io.github.redisops.sync.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.sync.contract.SyncCommandPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncCommandPolicyJsonCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsAndWritesPublishedV1Shape() throws Exception {
        SyncCommandPolicy policy = json.readValue("""
                {"allowDestructiveCommands":false,"allowSafeSplit":true,
                 "additionalBlockedCommands":["del"],"policyVersion":"v1"}
                """, SyncCommandPolicy.class);
        assertThat(policy.additionalBlockedCommands()).containsExactly("DEL");
        assertThat(json.readTree(json.writeValueAsString(policy))).isEqualTo(json.readTree("""
                {"allowDestructiveCommands":false,"allowSafeSplit":true,
                 "additionalBlockedCommands":["DEL"],"policyVersion":"v1"}
                """));
    }

    @Test
    void defaultsMissingOptionalFieldsAndRejectsUnknownVersion() throws Exception {
        SyncCommandPolicy defaults = json.readValue(
                "{\"allowDestructiveCommands\":false,\"allowSafeSplit\":true}", SyncCommandPolicy.class);
        assertThat(defaults.additionalBlockedCommands()).isEqualTo(Set.of());
        assertThat(defaults.policyVersion()).isEqualTo("v1");
        assertThatThrownBy(() -> json.readValue(
                "{\"allowDestructiveCommands\":false,\"allowSafeSplit\":true,\"policyVersion\":\"v2\"}",
                SyncCommandPolicy.class)).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
