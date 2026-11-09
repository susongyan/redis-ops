package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.RdbEvent;
import io.github.susongyan.redisops.worker.protocol.SplitOptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SplitKeyRestoreWriterTest {
    @Test
    void tempKeyIsDeterministicAndFingerprinted() {
        byte[] key = "big:user:list".getBytes(StandardCharsets.UTF_8);
        byte[] first = StandaloneSplitChannel.tempKeyOf(42, "standalone", key);
        assertArrayEquals(first, StandaloneSplitChannel.tempKeyOf(42, "standalone", key));
        assertTrue(new String(first, StandardCharsets.US_ASCII).startsWith("__ropstmppart__:{42}:standalone:"));
        assertEquals(16, first.length - "__ropstmppart__:{42}:standalone:".length());
        // distinct keys map to distinct temp names, same key across channels does not collide
        assertNotEquals(new String(first),
                new String(StandaloneSplitChannel.tempKeyOf(42, "standalone",
                        "other".getBytes(StandardCharsets.UTF_8))));
        assertNotEquals(new String(first),
                new String(StandaloneSplitChannel.tempKeyOf(43, "standalone", key)));
    }

    @Test
    void fingerprintIsSha256PrefixWithoutKeyContent() {
        String fingerprint = SplitKeyRestoreWriter.fingerprint("secret-key".getBytes(StandardCharsets.UTF_8));
        assertEquals(16, fingerprint.length());
        assertDoesNotThrow(() -> Long.parseLong(fingerprint.substring(0, 8), 16));
        assertTrue(fingerprint.matches("[0-9a-f]{16}"));
        assertNotEquals(fingerprint, SplitKeyRestoreWriter.fingerprint("secret-keY".getBytes()));
    }

    @Test
    void appendCommandMapsEverySplittableType() {
        byte[] temp = StandaloneSplitChannel.tempKeyOf(1, "standalone", new byte[]{1});
        var member = new RdbEvent.KeyElement(bytes("m"), null, null);
        var pair = new RdbEvent.KeyElement(bytes("f"), bytes("v"), null);
        var scored = new RdbEvent.KeyElement(bytes("m"), null, bytes("1.5"));
        assertCommand(new byte[][]{bytes("RPUSH"), temp, bytes("m")},
                SplitKeyRestoreWriter.appendCommand(1, temp, member));
        assertCommand(new byte[][]{bytes("RPUSH"), temp, bytes("m")},
                SplitKeyRestoreWriter.appendCommand(10, temp, member));
        assertCommand(new byte[][]{bytes("RPUSH"), temp, bytes("m")},
                SplitKeyRestoreWriter.appendCommand(14, temp, member));
        assertCommand(new byte[][]{bytes("RPUSH"), temp, bytes("m")},
                SplitKeyRestoreWriter.appendCommand(18, temp, member));
        assertCommand(new byte[][]{bytes("SADD"), temp, bytes("m")},
                SplitKeyRestoreWriter.appendCommand(2, temp, member));
        assertCommand(new byte[][]{bytes("SADD"), temp, bytes("m")},
                SplitKeyRestoreWriter.appendCommand(11, temp, member));
        assertCommand(new byte[][]{bytes("SADD"), temp, bytes("m")},
                SplitKeyRestoreWriter.appendCommand(20, temp, member));
        assertCommand(new byte[][]{bytes("ZADD"), temp, bytes("1.5"), bytes("m")},
                SplitKeyRestoreWriter.appendCommand(3, temp, scored));
        assertCommand(new byte[][]{bytes("ZADD"), temp, bytes("1.5"), bytes("m")},
                SplitKeyRestoreWriter.appendCommand(5, temp, scored));
        assertCommand(new byte[][]{bytes("HSET"), temp, bytes("f"), bytes("v")},
                SplitKeyRestoreWriter.appendCommand(4, temp, pair));
        assertCommand(new byte[][]{bytes("HSET"), temp, bytes("f"), bytes("v")},
                SplitKeyRestoreWriter.appendCommand(9, temp, pair));
        assertCommand(new byte[][]{bytes("HSET"), temp, bytes("f"), bytes("v")},
                SplitKeyRestoreWriter.appendCommand(13, temp, pair));
        assertCommand(new byte[][]{bytes("HSET"), temp, bytes("f"), bytes("v")},
                SplitKeyRestoreWriter.appendCommand(16, temp, pair));
        // stream/module types never reach the writer: the parser routes them elsewhere
        var blocked = assertThrows(SyncBlockedException.class,
                () -> SplitKeyRestoreWriter.appendCommand(15, temp, member));
        assertEquals("BLOCKED_UNSUPPORTED_SPLIT_TYPE", blocked.reason());
    }

    @Test
    void targetOomDetectionCoversRawAndWrappedErrors() throws IOException {
        assertTrue(FullRestorePool.isTargetOOM(
                new io.github.susongyan.redisops.worker.protocol.RespProtocolException(
                        "split key transaction failed: OOM command not allowed when used memory > 'maxmemory'")));
        assertTrue(FullRestorePool.isTargetOOM(
                new IOException("target master remained unavailable during full restore",
                        new RuntimeException("RESTORE failed: OOM command not allowed"))));
        assertFalse(FullRestorePool.isTargetOOM(new IOException("connection reset")));
        assertFalse(FullRestorePool.isTargetOOM(new IOException((String) null)));
    }

    @Test
    void settingsValidateAndConvert() {
        var settings = new SplitKeySettings(1024, 4096, 8, 8192, true);
        assertTrue(settings.enabled());
        SplitOptions options = settings.toParserOptions();
        assertEquals(1024, options.maxRestoreBytes());
        assertEquals(8192, options.hardCapBytes());
        assertTrue(options.skipUnsupportedTypes());
        var disabled = new SplitKeySettings(0, 65536, 128, 0, false);
        assertFalse(disabled.enabled());
        assertThrows(IllegalArgumentException.class, () -> new SplitKeySettings(1024, 4096, 8, 512, false));
        assertThrows(IllegalArgumentException.class, () -> new SplitKeySettings(1024, 512, 8, 8192, false));
        assertThrows(IllegalArgumentException.class, () -> new SplitKeySettings(1024, 4096, 0, 8192, false));
        // a disabled setting ignores batch caps: zero max restore skips their validation
        assertDoesNotThrow(() -> new SplitKeySettings(0, 0, 0, 0, false));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void assertCommand(byte[][] expected, byte[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            assertArrayEquals(expected[i], actual[i], "argument " + i);
    }
}
