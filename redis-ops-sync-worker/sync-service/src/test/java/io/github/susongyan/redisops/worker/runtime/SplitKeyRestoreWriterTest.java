package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.persistence.WorkerSyncExecutionPort;
import io.github.susongyan.redisops.worker.protocol.RdbEvent;
import io.github.susongyan.redisops.worker.protocol.SplitOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

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
    void skipEventMessageCarriesKeyNameAndDeclaredElementCount() {
        byte[] key = "biz:user:2024:followers".getBytes(StandardCharsets.UTF_8);
        assertEquals("SPLIT_KEY_SKIPPED rdbType=4 keyBytes=23 elements=20010000 payloadBytes=536870912"
                + " reason=OVER_HARD_CAP key=biz:user:2024:followers",
                SplitKeyRestoreWriter.skipEventMessage(key, 4, 536_870_912L, 20_010_000L, "OVER_HARD_CAP"));
        // opaque blob skip: element count unknown -> 0, still renders
        assertEquals("SPLIT_KEY_SKIPPED rdbType=0 keyBytes=6 elements=0 payloadBytes=1024"
                + " reason=OVER_HARD_CAP key=huge:s",
                SplitKeyRestoreWriter.skipEventMessage(bytes("huge:s"), 0, 1024, 0, "OVER_HARD_CAP"));
        // a space inside the key survives because key= rides last
        String spaced = SplitKeyRestoreWriter.skipEventMessage(bytes("a b c"), 1, 32, 3, "ELEMENT_OVER_BULK_LIMIT");
        assertTrue(spaced.endsWith("reason=ELEMENT_OVER_BULK_LIMIT key=a b c"), spaced);
    }

    @Test
    void midStreamSkipReportsTheParserEstimateNotTheBeginField() throws Exception {
        // task 37 regression: a quicklist begin zeroes the begin-event estimate (it counts nodes),
        // and the mid-stream abort used to re-emit that zero instead of the parser's exact header sum
        SplitTargetChannel target = Mockito.mock(SplitTargetChannel.class);
        WorkerSyncExecutionPort sync = Mockito.mock(WorkerSyncExecutionPort.class);
        FullSyncProgressTracker progress = new FullSyncProgressTracker(37L, "epoch-1", "standalone", 1,
                Mockito.mock(WorkerSyncExecutionPort.class));
        Mockito.when(target.protoMaxBulkLen()).thenReturn(536_870_912L);
        Mockito.when(target.tempKey(37L, "standalone", bytes("bk:{l2}"))).thenReturn(bytes("__ropstmppart__"));
        try (SplitKeyRestoreWriter writer = new SplitKeyRestoreWriter(target, 37L, "standalone", null, null,
                new SplitKeySettings(1024, 1_000_000, 10_000, 8_192, true), () -> {
                }, () -> {
                },
                progress, sync, new SimpleMeterRegistry())) {
            writer.begin(new RdbEvent.BigKeyValueBegin(0, bytes("bk:{l2}"), -1, 14, 82_279));
            writer.element(new RdbEvent.KeyElement(bytes("m1"), null, null));
            writer.element(new RdbEvent.KeyElement(bytes("m2"), null, null));
            writer.skipped(new RdbEvent.SkippedKey(bytes("bk:{l2}"), 14, 670_569_632L,
                    RdbEvent.SkipReason.OVER_HARD_CAP, 6_500_000L));
        }
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        Mockito.verify(sync).appendTaskEvent(Mockito.eq(37L), Mockito.eq("sync:split"), message.capture());
        assertTrue(message.getValue().endsWith("rdbType=14 keyBytes=7 elements=6500000"
                + " payloadBytes=670569632 reason=OVER_HARD_CAP key=bk:{l2}"), message.getValue());
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
