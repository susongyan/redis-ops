package io.github.redisops.sync.engine;

import io.github.redisops.sync.protocol.RedisSlot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClusterSlotKeyspaceTest {

    @Test
    void createsAStableUniqueHashTagForEveryRedisSlot() {
        var tags = new HashSet<String>();
        for (int slot = 0; slot < 16384; slot++) {
            String tag = ClusterSlotKeyspace.tag(slot);
            assertEquals(slot, RedisSlot.of(tag));
            tags.add(tag);
            assertEquals(slot, RedisSlot.of(ClusterSlotKeyspace.checkpoint(9, "source-a", slot)));
            assertEquals(slot, RedisSlot.of(ClusterSlotKeyspace.cursor(9, "source-a", slot)));
            assertEquals(slot, RedisSlot.of(ClusterSlotKeyspace.fence(9, "source-a", slot)));
            assertEquals(slot, RedisSlot.of(ClusterSlotKeyspace.heartbeat(9, "source-a", slot)));
            assertEquals(slot, RedisSlot.of(ClusterSlotKeyspace.fullProgress(9, "source-a", slot, 2)));
        }
        assertEquals(16384, tags.size());
    }

    @Test
    void rejectsInvalidSlots() {
        assertThrows(IllegalArgumentException.class, () -> ClusterSlotKeyspace.tag(-1));
        assertThrows(IllegalArgumentException.class, () -> ClusterSlotKeyspace.tag(16384));
    }
}
