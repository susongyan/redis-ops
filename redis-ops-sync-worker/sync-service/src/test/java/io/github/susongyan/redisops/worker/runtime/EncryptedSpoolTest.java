package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.ReplicationCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EncryptedSpoolTest {
    @Test
    void recordReadBudgetAppliesToReplayAndPruning(@TempDir Path path) throws Exception {
        try (var spool = new EncryptedSpool(path, 78, new byte[32], 1024, 1024 * 1024)) {
            spool.prepare();
            for (int i = 1; i <= 30; i++)
                spool.append(command("INCR", "counter", i, i));
            spool.limitCommandRecordReads(32);
            assertThrows(io.github.susongyan.redisops.worker.protocol.RespProtocolException.class,
                    () -> spool.forEachCommandAfter(0, command -> fail("must reject before delivery")));
            assertThrows(io.github.susongyan.redisops.worker.protocol.RespProtocolException.class,
                    () -> spool.pruneCommandsThrough(30));
        }
    }

    @Test
    void streamingReplayHasStableAppendBoundaryAndPropagatesConsumerFailure(@TempDir Path path) throws Exception {
        try (var spool = new EncryptedSpool(path, 77, new byte[32], 1024, 1024 * 1024)) {
            spool.prepare();
            for (int i = 1; i <= 50; i++)
                spool.append(command("INCR", "counter", i, i));
            List<Long> offsets = new ArrayList<>();
            spool.forEachCommandAfter(10, value -> {
                offsets.add(value.endOffset());
                if (offsets.size() == 1)
                    spool.append(command("INCR", "counter", 51, 51));
            });
            assertEquals(40, offsets.size());
            assertEquals(11L, offsets.get(0));
            assertEquals(50L, offsets.get(39));
            assertEquals(1, spool.commandsAfter(50).size());
            var expected = new java.io.IOException("consumer stopped");
            assertSame(expected, assertThrows(java.io.IOException.class,
                    () -> spool.forEachCommandAfter(0, value -> {
                        throw expected;
                    })));
        }
    }

    @Test
    void preventsTwoProcessesFromOwningTheSameTaskSpool(@TempDir Path directory) throws Exception {
        byte[] key = new byte[32];
        EncryptedSpool first = new EncryptedSpool(directory, 7, key, 1024, 1024 * 1024);
        EncryptedSpool second = new EncryptedSpool(directory, 7, key, 1024, 1024 * 1024);
        try {
            first.prepare();
            assertThrows(EncryptedSpool.SpoolLockedException.class, second::prepare);
            first.close();
            assertDoesNotThrow(second::prepare);
        } finally {
            first.close();
            second.close();
        }
    }

    @TempDir
    Path directory;

    @Test
    void encryptsRdbAndCommandRecordsAndRestoresThem() throws Exception {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        try (EncryptedSpool spool = new EncryptedSpool(directory, 9, key, 1024, 1024 * 1024)) {
            spool.prepare();
            byte[] rdb = "REDIS0009-payload".getBytes(StandardCharsets.US_ASCII);
            spool.writeRdb(new ByteArrayInputStream(rdb), rdb.length, null);
            assertArrayEquals(rdb, spool.openRdb().readAllBytes());

            spool.append(command("INCR", "counter", 1, 24));
            spool.append(command("LPUSH", "items", 25, 52));
            List<ReplicationCommand> commands = spool.commandsAfter(24);
            assertEquals(1, commands.size());
            assertEquals("LPUSH", commands.get(0).name());

            byte[] encrypted = Files.readAllBytes(spool.directory().resolve("full.rdb.enc"));
            assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("REDIS0009-payload"));
        }
    }

    @Test
    void reportsCumulativeRdbReceiveProgress() throws Exception {
        byte[] key = new byte[32];
        byte[] rdb = new byte[64 * 1024 * 2 + 17];
        List<Long> progress = new ArrayList<>();
        try (EncryptedSpool spool = new EncryptedSpool(directory, 12, key, 1024, 1024 * 1024)) {
            spool.prepare();
            long written = spool.writeRdb(new ByteArrayInputStream(rdb), rdb.length, null, progress::add);

            assertEquals(rdb.length, written);
            assertFalse(progress.isEmpty());
            assertEquals(rdb.length, progress.get(progress.size() - 1));
            for (int index = 1; index < progress.size(); index++)
                assertTrue(progress.get(index) > progress.get(index - 1));
        }
    }

    @Test
    void supportsDisklessEofWithoutConsumingFollowingCommand() throws Exception {
        byte[] key = new byte[32];
        String marker = "0123456789012345678901234567890123456789";
        byte[] rdb = "REDIS0009-body".getBytes(StandardCharsets.US_ASCII);
        byte[] following = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] stream = new byte[rdb.length + marker.length() + following.length];
        System.arraycopy(rdb, 0, stream, 0, rdb.length);
        System.arraycopy(marker.getBytes(StandardCharsets.US_ASCII), 0, stream, rdb.length, marker.length());
        System.arraycopy(following, 0, stream, rdb.length + marker.length(), following.length);
        PushbackInputStream input = new PushbackInputStream(new ByteArrayInputStream(stream), 64 * 1024);
        try (EncryptedSpool spool = new EncryptedSpool(directory, 10, key, 1024, 1024 * 1024)) {
            spool.prepare();
            spool.writeRdb(input, -1, marker);
            assertArrayEquals(rdb, spool.openRdb().readAllBytes());
            assertArrayEquals(following, input.readAllBytes());
        }
    }

    @Test
    void rejectsTamperedRdbCiphertext() throws Exception {
        byte[] key = new byte[32];
        try (EncryptedSpool spool = new EncryptedSpool(directory, 11, key, 1024, 1024 * 1024)) {
            spool.prepare();
            byte[] rdb = "REDIS0009-body".getBytes(StandardCharsets.US_ASCII);
            spool.writeRdb(new ByteArrayInputStream(rdb), rdb.length, null);
            Path path = spool.directory().resolve("full.rdb.enc");
            byte[] encrypted = Files.readAllBytes(path);
            encrypted[encrypted.length - 1] ^= 1;
            Files.write(path, encrypted);
            assertThrows(Exception.class, () -> {
                try (var input = spool.openRdb()) {
                    input.readAllBytes();
                }
            });
        }
    }

    @Test
    void roundTripsRdbPayloadsSpanningMultipleChunks() throws Exception {
        byte[] key = new byte[32];
        byte[] rdb = new byte[256 * 1024 * 2 + 123];
        new java.security.SecureRandom().nextBytes(rdb);
        rdb[0] = 'R';
        rdb[1] = 'E';
        try (EncryptedSpool spool = new EncryptedSpool(directory, 13, key, 1024, 1024 * 1024 * 1024)) {
            spool.prepare();
            assertEquals(rdb.length, spool.writeRdb(new ByteArrayInputStream(rdb), rdb.length, null));
            assertArrayEquals(rdb, spool.openRdb().readAllBytes());
        }
    }

    @Test
    void rejectsTruncatedRdbSpoolMissingEndMarker() throws Exception {
        byte[] key = new byte[32];
        byte[] rdb = new byte[256 * 1024 + 7];
        try (EncryptedSpool spool = new EncryptedSpool(directory, 14, key, 1024, 1024 * 1024 * 1024)) {
            spool.prepare();
            spool.writeRdb(new ByteArrayInputStream(rdb), rdb.length, null);
            Path path = spool.directory().resolve("full.rdb.enc");
            byte[] encrypted = Files.readAllBytes(path);
            Files.write(path, java.util.Arrays.copyOf(encrypted, encrypted.length - 20));
            assertThrows(java.io.EOFException.class, () -> {
                try (var input = spool.openRdb()) {
                    input.readAllBytes();
                }
            });
        }
    }

    @Test
    void stillReadsLegacyCipherStreamSpoolFiles() throws Exception {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 9);
        byte[] rdb = "REDIS0009-legacy-payload".getBytes(StandardCharsets.US_ASCII);
        try (EncryptedSpool spool = new EncryptedSpool(directory, 15, key, 1024, 1024 * 1024)) {
            spool.prepare();
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            cipher.updateAAD(("redis-ops:sync-spool:15:rdb").getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(rdb);
            java.io.ByteArrayOutputStream legacy = new java.io.ByteArrayOutputStream();
            legacy.writeBytes(new byte[]{'R', 'S', 'P', '1'});
            legacy.writeBytes(iv);
            legacy.writeBytes(encrypted);
            Files.write(spool.directory().resolve("full.rdb.enc"), legacy.toByteArray());
            assertArrayEquals(rdb, spool.openRdb().readAllBytes());
        }
    }

    @Test
    void plainModeSpoolsRdbAndCommandsWithoutCiphertextOnDisk() throws Exception {
        byte[] key = new byte[32];
        try (EncryptedSpool spool = new EncryptedSpool(directory, 21, null, key, SpoolEncryption.NONE, 1024,
                1024 * 1024)) {
            spool.prepare();
            byte[] rdb = "REDIS0009-plain-payload".getBytes(StandardCharsets.US_ASCII);
            spool.writeRdb(new ByteArrayInputStream(rdb), rdb.length, null);
            assertArrayEquals(rdb, spool.openRdb().readAllBytes());

            spool.append(command("INCR", "counter", 1, 24));
            spool.append(command("LPUSH", "items", 25, 52));
            List<ReplicationCommand> commands = spool.commandsAfter(24);
            assertEquals(1, commands.size());
            assertEquals("LPUSH", commands.get(0).name());

            Path rdbFile = spool.directory().resolve("full.rdb.plain");
            assertTrue(Files.exists(rdbFile), "plain mode must write full.rdb.plain");
            assertFalse(Files.exists(spool.directory().resolve("full.rdb.enc")));
            assertTrue(new String(Files.readAllBytes(rdbFile), StandardCharsets.ISO_8859_1)
                    .contains("REDIS0009-plain-payload"), "NONE mode stores the RDB unencrypted");
            Path segment = spool.directory().resolve("commands-00000000.pseg");
            assertTrue(Files.exists(segment), "plain mode must write .pseg segments");
        }
    }

    @Test
    void plainModeWorksWhenNoCredentialKeyIsConfigured() throws Exception {
        try (EncryptedSpool spool = new EncryptedSpool(directory, 22, null, SpoolEncryption.NONE, 1024,
                1024 * 1024)) {
            spool.prepare();
            byte[] rdb = "REDIS0009-no-key".getBytes(StandardCharsets.US_ASCII);
            spool.writeRdb(new ByteArrayInputStream(rdb), rdb.length, null);
            assertArrayEquals(rdb, spool.openRdb().readAllBytes());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new EncryptedSpool(directory, 23, null, SpoolEncryption.AES_GCM, 1024, 1024 * 1024));
    }

    @Test
    void modeSwitchKeepsSpoolsFromTheOtherModeReadable() throws Exception {
        byte[] key = new byte[32];
        byte[] rdb = "REDIS0009-switch".getBytes(StandardCharsets.US_ASCII);
        try (EncryptedSpool encrypted = new EncryptedSpool(directory, 24, key, 1024, 1024 * 1024)) {
            encrypted.prepare();
            encrypted.writeRdb(new ByteArrayInputStream(rdb), rdb.length, null);
        }
        try (EncryptedSpool plain = new EncryptedSpool(directory, 24, null, key, SpoolEncryption.NONE, 1024,
                1024 * 1024)) {
            plain.prepare();
            assertArrayEquals(rdb, plain.openRdb().readAllBytes(), "NONE-mode worker must read old AES spools");
        }
        try (EncryptedSpool plain = new EncryptedSpool(directory, 25, null, key, SpoolEncryption.NONE, 1024,
                1024 * 1024)) {
            plain.prepare();
            plain.writeRdb(new ByteArrayInputStream(rdb), rdb.length, null);
        }
        try (EncryptedSpool encrypted = new EncryptedSpool(directory, 25, key, 1024, 1024 * 1024)) {
            encrypted.prepare();
            assertArrayEquals(rdb, encrypted.openRdb().readAllBytes(), "AES-mode worker must read plain spools");
        }
    }

    @Test
    void spoolEncryptionParsesConfigValues() {
        assertEquals(SpoolEncryption.NONE, SpoolEncryption.parse(null));
        assertEquals(SpoolEncryption.NONE, SpoolEncryption.parse(" "));
        assertEquals(SpoolEncryption.AES_GCM, SpoolEncryption.parse("AES_GCM"));
        assertEquals(SpoolEncryption.AES_GCM, SpoolEncryption.parse("aes-gcm"));
        assertEquals(SpoolEncryption.NONE, SpoolEncryption.parse("none"));
        assertEquals(SpoolEncryption.NONE, SpoolEncryption.parse("PLAIN"));
        assertThrows(IllegalArgumentException.class, () -> SpoolEncryption.parse("des"));
    }

    private static ReplicationCommand command(String name, String key, long start, long end) {
        return new ReplicationCommand(name, List.of(name.getBytes(StandardCharsets.US_ASCII),
                key.getBytes(StandardCharsets.US_ASCII)), start, end);
    }
}
