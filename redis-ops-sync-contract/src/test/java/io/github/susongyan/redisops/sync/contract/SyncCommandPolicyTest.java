package io.github.susongyan.redisops.sync.contract;

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
    void destructiveCapabilitiesRemainBlockedRegardlessOfTopologyOrLegacyFlag() {
        for (boolean cluster : new boolean[]{false, true})
            for (boolean allowed : new boolean[]{false, true})
                for (String command : Set.of("FLUSHDB", "FLUSHALL")) {
                    var capability = SyncCommandCapabilities.classify(command, cluster,
                            new SyncCommandPolicy(allowed, true, Set.of(), "v1"));
                    assertEquals("HARD_BLOCKED", capability.category());
                    assertTrue(capability.currentlyBlocked());
                    assertFalse(capability.configurable());
                }
    }

    @Test
    void rejectsInvalidCommandNamesAndPolicyVersions() {
        assertThrows(IllegalArgumentException.class,
                () -> new SyncCommandPolicy(false, true, Set.of("DEL *"), "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new SyncCommandPolicy(false, true, Set.of(), "v999"));
    }

    @Test
    void multiKeyIsOptInAndMissingVersionsStayLegacy() {
        assertEquals("v1", new SyncCommandPolicy(false, true, Set.of(), null).policyVersion());
        assertFalse(SyncCommandPolicy.strict().supportsMultiKey());
        var newer = new SyncCommandPolicy(false, true, Set.of(), "v2");
        assertTrue(newer.supportsMultiKey());
        assertEquals("CONDITIONAL", SyncCommandCapabilities.classify("BITOP", true, newer).category());
        assertTrue(SyncCommandCapabilities.classify("BITOP", true, SyncCommandPolicy.strict()).currentlyBlocked());
    }

    @Test
    void unknownCommandsFailClosed() {
        var capability = SyncCommandCapabilities.classify("FUTURECMD", false, SyncCommandPolicy.strict());
        assertTrue(capability.currentlyBlocked());
        assertEquals("UNKNOWN_BLOCKED", capability.category());
    }

    @Test
    void exposesPlannerClassificationQueriesAsPartOfThePublishedContract() {
        assertTrue(SyncCommandCapabilities.singleKey("SET"));
        assertTrue(SyncCommandCapabilities.safeSplit("MSET"));
        assertTrue(SyncCommandCapabilities.hardBlocked("EVAL"));
        assertTrue(SyncCommandCapabilities.destructive("FLUSHDB"));
        assertTrue(SyncCommandCapabilities.skipped("REPLCONF"));
        assertFalse(SyncCommandCapabilities.singleKey("FUTURECMD"));
    }
}
