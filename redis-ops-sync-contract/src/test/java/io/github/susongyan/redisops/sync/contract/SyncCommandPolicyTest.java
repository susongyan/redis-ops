package io.github.susongyan.redisops.sync.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SyncCommandPolicyTest {
    @Test
    void redis5TransactionsAreV3OnlyWithoutOpeningUntestedVersionLines() {
        for (String version : Set.of("v1", "v2", "v3")) {
            var policy = new SyncCommandPolicy(false, true, Set.of(), version);
            assertEquals(version.equals("v3"), policy.supportsMultiKeyRedisVersion(5, 0));
            assertEquals(!version.equals("v1"), policy.supportsMultiKeyRedisVersion(6, 2));
            assertEquals(!version.equals("v1"), policy.supportsMultiKeyRedisVersion(7, 4));
            assertFalse(policy.supportsMultiKeyRedisVersion(4, 0));
            assertFalse(policy.supportsMultiKeyRedisVersion(5, 1));
            assertFalse(policy.supportsMultiKeyRedisVersion(6, 0));
            assertFalse(policy.supportsMultiKeyRedisVersion(8, 0));
            assertTrue(SyncCommandCapabilities.classify("EVAL", false, policy).currentlyBlocked());
        }
    }
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
    void transactionsRequireExplicitV3AndNeverEnableOriginalScripts() {
        for (String version : Set.of("v1", "v2")) {
            var legacy = new SyncCommandPolicy(false, true, Set.of(), version);
            assertFalse(legacy.supportsTransactions());
            assertTrue(SyncCommandCapabilities.classify("MULTI", false, legacy).currentlyBlocked());
        }
        var policy = new SyncCommandPolicy(false, true, Set.of(), "v3");
        assertTrue(policy.supportsTransactions());
        assertTrue(policy.supportsMultiKey());
        assertFalse(SyncCommandCapabilities.classify("MULTI", true, policy).currentlyBlocked());
        assertTrue(SyncCommandCapabilities.classify("EVAL", false, policy).currentlyBlocked());
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
