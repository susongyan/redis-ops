package io.github.redisops.sync.engine;

import io.github.redisops.sync.protocol.ReplicationCommand;
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

    private static ReplicationCommand command(String name, String key, long start, long end) {
        return new ReplicationCommand(name, List.of(name.getBytes(StandardCharsets.US_ASCII),
                key.getBytes(StandardCharsets.US_ASCII)), start, end);
    }
}
