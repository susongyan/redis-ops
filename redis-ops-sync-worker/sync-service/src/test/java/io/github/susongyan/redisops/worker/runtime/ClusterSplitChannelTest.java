package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.RedisSlot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ClusterSplitChannelTest {
    @Test
    void tempKeySharesTheRealKeySlotForEveryNamingCase() {
        // case 1: hash-tagged keys — the tag is wrapped, not the raw key (the original design
        // sketch wrapped the raw key and would hash "user:{123", landing on a different slot)
        assertSameSlot("user:{123}:profile");
        assertSameSlot("{123}");
        assertSameSlot("{123}:a", "{123}:b"); // same tag: same temp name, same slot
        // tag containing '{' but no '}' is itself a valid tag and wraps exactly
        assertSameSlot("x{a{b}y");
        // case 2: untagged keys hash whole — brace-wrapping the whole key reproduces the hash
        assertSameSlot("plainkey");
        assertSameSlot("prefix:suffix");
        assertSameSlot("open{brace");
        // case 3: untagged keys containing '}' cannot be wrapped — collision tag takes over
        assertSameSlot("close}brace");
        assertSameSlot("}leading");
        assertSameSlot("a{}b}c"); // empty tag hashes whole, '}' forbids wrapping
        assertSameSlot("a}b{c");
    }

    @Test
    void tempKeyIsDeterministicAndFingerprintFree() {
        byte[] key = "big:user:{tag}:list".getBytes(StandardCharsets.UTF_8);
        byte[] first = ClusterSplitChannel.clusterTempKey(42, "c0", key);
        assertArrayEquals(first, ClusterSplitChannel.clusterTempKey(42, "c0", key));
        String printable = new String(first, StandardCharsets.US_ASCII);
        assertTrue(printable.contains("__ropstmppart__:42:c0"), printable);
        assertFalse(printable.contains("big:user"), "temp name must not embed the key content");
        // taskId/channel ride outside the tag: different tasks or channels get different names
        assertNotEquals(printable, new String(ClusterSplitChannel.clusterTempKey(43, "c0", key),
                StandardCharsets.US_ASCII));
        assertNotEquals(printable, new String(ClusterSplitChannel.clusterTempKey(42, "c1", key),
                StandardCharsets.US_ASCII));
    }

    @Test
    void taggedKeysWrapOnlyTheEffectiveTag() {
        byte[] temp = ClusterSplitChannel.clusterTempKey(7, "c0", "user:{123}:profile".getBytes());
        String printable = new String(temp, StandardCharsets.US_ASCII);
        assertTrue(printable.startsWith("{123}:__ropstmppart__:7:c0"), printable);
    }

    @Test
    void untaggedKeysWithoutBraceWrapTheWholeKey() {
        byte[] temp = ClusterSplitChannel.clusterTempKey(7, "c0", "plain:key".getBytes());
        assertEquals("{plain:key}:__ropstmppart__:7:c0", new String(temp, StandardCharsets.US_ASCII));
    }

    private static void assertSameSlot(String key) {
        assertSameSlot(key, key);
    }

    private static void assertSameSlot(String key, String sharesTempWith) {
        byte[] real = key.getBytes(StandardCharsets.UTF_8);
        byte[] temp = ClusterSplitChannel.clusterTempKey(42, "c0", real);
        assertEquals(RedisSlot.of(real), RedisSlot.of(temp), "temp key slot must match for " + key);
        assertArrayEquals(temp, ClusterSplitChannel.clusterTempKey(42, "c0",
                sharesTempWith.getBytes(StandardCharsets.UTF_8)));
    }
}
