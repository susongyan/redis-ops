package io.github.susongyan.redisops.worker.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetCommandSessionOwnedKeysTest {
    private static byte[] key(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void exemptsOwnTaskKeysAcrossTypesChannelsAndSlotTags() {
        long taskId = 5;
        assertTrue(TargetCommandSession.ownedByTask(key("__redis_ops_sync_fence__:{rs21k}:5:master-abc"), taskId));
        assertTrue(TargetCommandSession.ownedByTask(key("__redis_ops_sync_cursor__:{rs8dt}:5:master-abc"), taskId));
        assertTrue(TargetCommandSession.ownedByTask(
                key("__redis_ops_sync_full_progress__:{rs21k}:5:master-abc:3"), taskId));
        assertTrue(TargetCommandSession.ownedByTask(key("__redis_ops_sync_ckpt__:{5}:channel-a"), taskId));
        assertTrue(TargetCommandSession.ownedByTask(key("__redis_ops_sync_hb__:{5}:channel-a"), taskId));
    }

    @Test
    void blocksOtherTaskAndForeignKeys() {
        assertFalse(TargetCommandSession.ownedByTask(key("__redis_ops_sync_fence__:{rs21k}:6:master-abc"), 5));
        assertFalse(TargetCommandSession.ownedByTask(key("__redis_ops_sync_ckpt__:{51}:channel-a"), 5));
        assertFalse(TargetCommandSession.ownedByTask(key("business:key"), 5));
        assertFalse(TargetCommandSession.ownedByTask(key("__redis_ops_sync_unknown__:{5}:channel-a"), 5));
        assertFalse(TargetCommandSession.ownedByTask(key("__redis_ops_sync_ckpt__"), 5));
    }

    @Test
    void parsesOwnerTaskIdFromStandaloneAndClusterLayouts() {
        assertTrue(TargetCommandSession.ownerTaskId(key("__redis_ops_sync_fence__:{7}:channel-a")).getAsLong() == 7);
        assertTrue(TargetCommandSession.ownerTaskId(
                key("__redis_ops_sync_full_progress__:{rs21k}:9:master-abc:3")).getAsLong() == 9);
        assertTrue(TargetCommandSession.ownerTaskId(
                key("__redis_ops_sync_hb__:{rs8dt}:12:master-abc")).getAsLong() == 12);
    }

    @Test
    void unparsableKeysHaveNoOwner() {
        assertFalse(TargetCommandSession.ownerTaskId(key("business:key")).isPresent());
        assertFalse(TargetCommandSession.ownerTaskId(key("__redis_ops_sync_unknown__:{5}:ch")).isPresent());
        assertFalse(TargetCommandSession.ownerTaskId(key("__redis_ops_sync_ckpt__:{rs21k}:x:ch")).isPresent());
        assertFalse(TargetCommandSession.ownerTaskId(key("__redis_ops_sync_ckpt__:{0}:ch")).isPresent());
    }
}
