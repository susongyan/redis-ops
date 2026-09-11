package io.github.redisops.sync.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SyncCommandPolicyTest {
    @Test
    void normalizesAdditionalBlocksAndKeepsHardBlocksClosed() {
        var policy = new SyncCommandPolicy(false, true, Set.of("del", "eval"), "v1");

        assertTrue(policy.additionallyBlocks("DEL"));
        assertEquals("HARD_BLOCKED",
                SyncCommandCapabilities.classify("EVAL", false, policy).category());
        assertFalse(SyncCommandCapabilities.classify("EVAL", false, policy).configurable());
    }

    @Test
    void clusterDestructiveCommandsCannotBeEnabled() {
        var policy = new SyncCommandPolicy(true, true, Set.of(), "v1");

        var capability = SyncCommandCapabilities.classify("FLUSHALL", true, policy);
        assertTrue(capability.currentlyBlocked());
        assertFalse(capability.configurable());
    }

    @Test
    void rejectsInvalidCommandNamesAndPolicyVersions() {
        assertThrows(IllegalArgumentException.class,
                () -> new SyncCommandPolicy(false, true, Set.of("DEL *"), "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new SyncCommandPolicy(false, true, Set.of(), "v2"));
    }

    @Test
    void unknownCommandsFailClosed() {
        var capability = SyncCommandCapabilities.classify("FUTURECMD", false, SyncCommandPolicy.strict());
        assertTrue(capability.currentlyBlocked());
        assertEquals("UNKNOWN_BLOCKED", capability.category());
    }
}
