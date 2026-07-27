package io.github.redisops.sync.engine;

import io.github.redisops.sync.protocol.ReplicationCommand;
import io.github.redisops.sync.protocol.RespCodec;
import io.github.redisops.sync.protocol.RespProtocolException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

public final class EncryptedSpool implements AutoCloseable {
    private static final byte[] RDB_MAGIC = {'R', 'S', 'P', '1'};
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path directory;
    private final long taskId;
    private final byte[] key;
    private final long segmentBytes;
    private final long limitBytes;
    private DataOutputStream commandOutput;
    private FileOutputStream commandFile;
    private int segment;
    private int activeSegment = -1;
    private long segmentSize;
    private long bytes;
    private FileChannel lockChannel;
    private FileLock directoryLock;

    public EncryptedSpool(Path dataDirectory, long taskId, byte[] key, long segmentBytes, long limitBytes) {
        if (segmentBytes < 1024)
            throw new IllegalArgumentException("segmentBytes must be at least 1024");
        this.directory = dataDirectory.resolve(Long.toString(taskId));
        this.taskId = taskId;
        this.key = key.clone();
        this.segmentBytes = segmentBytes;
        this.limitBytes = limitBytes;
    }

    public void prepare() throws IOException {
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve(".owner.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            directoryLock = lockChannel.tryLock();
        } catch (OverlappingFileLockException error) {
            closeLock();
            throw new SpoolLockedException("sync spool is already owned by another process", error);
        }
        if (directoryLock == null) {
            closeLock();
            throw new SpoolLockedException("sync spool is already owned by another process");
        }
        bytes = directoryBytes();
        if (bytes > limitBytes)
            throw new IllegalStateException("sync spool already exceeds configured limit");
        segment = existingSegments().stream().mapToInt(this::segmentNumber).max().orElse(-1) + 1;
    }

    public long writeRdb(InputStream source, long length, String eofMarker) throws IOException {
        Path target = directory.resolve("full.rdb.enc");
        Path temporary = directory.resolve("full.rdb.enc.tmp");
        byte[] iv = randomIv();
        long plainBytes;
        try (FileOutputStream file = new FileOutputStream(temporary.toFile());
                DataOutputStream header = new DataOutputStream(file)) {
            header.write(RDB_MAGIC);
            header.write(iv);
            header.flush();
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv, aad("rdb"));
            OutputStream nonClosing = new FilterOutputStream(file) {
                @Override
                public void close() throws IOException {
                    flush();
                }
            };
            try (CipherOutputStream encrypted = new CipherOutputStream(nonClosing, cipher)) {
                plainBytes = length >= 0
                        ? copyFixed(source, encrypted, length)
                        : copyUntilMarker(source, encrypted, Objects.requireNonNull(eofMarker));
            }
            file.getChannel().force(true);
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        bytes = directoryBytes();
        ensureLimit();
        return plainBytes;
    }

    public InputStream openRdb() throws IOException {
        DataInputStream input = new DataInputStream(Files.newInputStream(directory.resolve("full.rdb.enc")));
        byte[] magic = input.readNBytes(RDB_MAGIC.length);
        if (!Arrays.equals(magic, RDB_MAGIC)) {
            input.close();
            throw new RespProtocolException("invalid encrypted RDB spool header");
        }
        byte[] iv = input.readNBytes(IV_BYTES);
        if (iv.length != IV_BYTES) {
            input.close();
            throw new EOFException("truncated encrypted RDB spool header");
        }
        return new CipherInputStream(input, cipher(Cipher.DECRYPT_MODE, iv, aad("rdb")));
    }

    public void saveFullMetadata(String replicationId, long baseOffset) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("replicationId", replicationId);
        metadata.setProperty("baseOffset", Long.toString(baseOffset));
        Path temporary = directory.resolve("full.meta.tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            metadata.store(output, "Redis Ops sync spool metadata");
        }
        Files.move(temporary, directory.resolve("full.meta"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        bytes = directoryBytes();
    }

    public Optional<FullMetadata> fullMetadata() throws IOException {
        Path path = directory.resolve("full.meta");
        if (!Files.exists(path))
            return Optional.empty();
        Properties metadata = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            metadata.load(input);
        }
        String replicationId = metadata.getProperty("replicationId");
        String offset = metadata.getProperty("baseOffset");
        if (replicationId == null || offset == null)
            throw new RespProtocolException("invalid full sync spool metadata");
        try {
            return Optional.of(new FullMetadata(replicationId, Long.parseLong(offset)));
        } catch (NumberFormatException error) {
            throw new RespProtocolException("invalid full sync spool metadata", error);
        }
    }

    public synchronized void append(ReplicationCommand command) throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(plain)) {
            data.writeLong(command.startOffset());
            data.writeLong(command.endOffset());
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            new RespCodec(InputStream.nullInputStream(), wire)
                    .writeCommand(command.arguments().toArray(byte[][]::new));
            byte[] encoded = wire.toByteArray();
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(encoded);
            data.writeLong(crc.getValue());
            data.writeInt(encoded.length);
            data.write(encoded);
        }
        long recordBytes = Integer.BYTES + IV_BYTES + plain.size() + 16L;
        if (commandOutput == null || segmentSize > 0 && segmentSize + recordBytes > segmentBytes)
            rotate();
        byte[] iv = randomIv();
        byte[] encrypted;
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv, aad("command:" + activeSegment));
            encrypted = cipher.doFinal(plain.toByteArray());
        } catch (Exception error) {
            throw new IOException("cannot encrypt sync spool record", error);
        }
        commandOutput.writeInt(IV_BYTES + encrypted.length);
        commandOutput.write(iv);
        commandOutput.write(encrypted);
        commandOutput.flush();
        commandFile.getChannel().force(false);
        segmentSize += recordBytes;
        bytes += recordBytes;
        ensureLimit();
    }

    public List<ReplicationCommand> commandsAfter(long appliedOffset) throws IOException {
        List<ReplicationCommand> result = new ArrayList<>();
        for (Path path : existingSegments())
            for (ReplicationCommand command : readSegment(path))
                if (command.endOffset() > appliedOffset)
                    result.add(command);
        return result;
    }

    public synchronized void discardFullRdb() throws IOException {
        Files.deleteIfExists(directory.resolve("full.rdb.enc"));
        Files.deleteIfExists(directory.resolve("full.meta"));
        bytes = directoryBytes();
    }

    public synchronized void pruneCommandsThrough(long appliedOffset) throws IOException {
        for (Path path : existingSegments()) {
            if (segmentNumber(path) == activeSegment)
                continue;
            List<ReplicationCommand> commands = readSegment(path);
            if (commands.isEmpty() || commands.get(commands.size() - 1).endOffset() <= appliedOffset)
                Files.deleteIfExists(path);
        }
        bytes = directoryBytes();
    }

    public synchronized long bytes() {
        return bytes;
    }

    public Path directory() {
        return directory;
    }

    @Override
    public synchronized void close() {
        if (commandOutput != null) {
            try {
                commandOutput.close();
            } catch (IOException ignored) {
                // Best effort during shutdown.
            }
            commandOutput = null;
            commandFile = null;
        }
        Arrays.fill(key, (byte) 0);
        closeLock();
    }

    private ReplicationCommand decodeCommand(byte[] plain) throws IOException {
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(plain))) {
            long start = data.readLong();
            long end = data.readLong();
            long expectedCrc = data.readLong();
            int length = data.readInt();
            if (length < 0 || length > plain.length)
                throw new RespProtocolException("invalid command spool payload");
            byte[] wire = data.readNBytes(length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(wire);
            if (crc.getValue() != expectedCrc)
                throw new RespProtocolException("sync spool record CRC mismatch");
            var value = new RespCodec(new ByteArrayInputStream(wire), OutputStream.nullOutputStream()).read();
            if (!(value instanceof io.github.redisops.sync.protocol.RespValue.Array array))
                throw new RespProtocolException("spooled command is not an array");
            List<byte[]> arguments = new ArrayList<>();
            for (var item : array.values()) {
                if (!(item instanceof io.github.redisops.sync.protocol.RespValue.Bulk bulk))
                    throw new RespProtocolException("spooled command argument is not bulk");
                arguments.add(bulk.value());
            }
            String name = new String(arguments.get(0), StandardCharsets.US_ASCII);
            return new ReplicationCommand(name, arguments, start, end);
        }
    }

    private void rotate() throws IOException {
        if (commandOutput != null)
            commandOutput.close();
        activeSegment = segment++;
        Path path = segmentPath(activeSegment);
        commandFile = new FileOutputStream(path.toFile(), true);
        commandOutput = new DataOutputStream(new BufferedOutputStream(commandFile, 64 * 1024));
        segmentSize = Files.size(path);
    }

    private List<ReplicationCommand> readSegment(Path path) throws IOException {
        int fileSegment = segmentNumber(path);
        List<ReplicationCommand> result = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            while (true) {
                int length;
                try {
                    length = input.readInt();
                } catch (EOFException end) {
                    break;
                }
                if (length < IV_BYTES + 16 || length > limitBytes)
                    throw new RespProtocolException("invalid encrypted spool record length");
                byte[] iv = input.readNBytes(IV_BYTES);
                byte[] encrypted = input.readNBytes(length - IV_BYTES);
                if (iv.length != IV_BYTES || encrypted.length != length - IV_BYTES)
                    throw new EOFException("truncated encrypted spool record");
                byte[] plain;
                try {
                    plain = cipher(Cipher.DECRYPT_MODE, iv, aad("command:" + fileSegment))
                            .doFinal(encrypted);
                } catch (Exception error) {
                    throw new IOException("sync spool authentication failed", error);
                }
                result.add(decodeCommand(plain));
            }
        }
        return result;
    }

    private List<Path> existingSegments() throws IOException {
        if (!Files.exists(directory))
            return List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(x -> x.getFileName().toString().matches("commands-\\d{8}\\.seg"))
                    .sorted().toList();
        }
    }

    private Path segmentPath(int number) {
        return directory.resolve("commands-%08d.seg".formatted(number));
    }

    private int segmentNumber(Path path) {
        String name = path.getFileName().toString();
        return Integer.parseInt(name.substring(9, 17));
    }

    private long directoryBytes() throws IOException {
        if (!Files.exists(directory))
            return 0;
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !".owner.lock".equals(path.getFileName().toString()))
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    }).sum();
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
    }

    private void ensureLimit() {
        if (bytes > limitBytes)
            throw new IllegalStateException("sync spool limit exceeded");
    }

    private Cipher cipher(int mode, byte[] iv, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher;
        } catch (Exception error) {
            throw new IllegalStateException("cannot initialize sync spool cipher", error);
        }
    }

    private byte[] aad(String kind) {
        return ("redis-ops:sync-spool:" + taskId + ":" + kind).getBytes(StandardCharsets.US_ASCII);
    }

    private void closeLock() {
        if (directoryLock != null) {
            try {
                directoryLock.release();
            } catch (IOException ignored) {
                // Best effort during shutdown.
            }
            directoryLock = null;
        }
        if (lockChannel != null) {
            try {
                lockChannel.close();
            } catch (IOException ignored) {
                // Best effort during shutdown.
            }
            lockChannel = null;
        }
    }

    private static byte[] randomIv() {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    private static long copyFixed(InputStream input, OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0)
                throw new EOFException("truncated fixed-length RDB transfer");
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return length;
    }

    private static long copyUntilMarker(InputStream source, OutputStream output, String markerValue)
            throws IOException {
        if (!(source instanceof PushbackInputStream input))
            throw new IllegalArgumentException("diskless RDB source must support pushback");
        byte[] marker = markerValue.getBytes(StandardCharsets.US_ASCII);
        byte[] tail = new byte[0];
        byte[] chunk = new byte[64 * 1024];
        long written = 0;
        while (true) {
            int read = input.read(chunk);
            if (read < 0)
                throw new EOFException("diskless RDB EOF marker was not received");
            byte[] combined = new byte[tail.length + read];
            System.arraycopy(tail, 0, combined, 0, tail.length);
            System.arraycopy(chunk, 0, combined, tail.length, read);
            int markerAt = indexOf(combined, marker);
            if (markerAt >= 0) {
                output.write(combined, 0, markerAt);
                written += markerAt;
                int after = combined.length - markerAt - marker.length;
                if (after > 0)
                    input.unread(combined, markerAt + marker.length, after);
                return written;
            }
            int safe = Math.max(0, combined.length - marker.length + 1);
            output.write(combined, 0, safe);
            written += safe;
            tail = Arrays.copyOfRange(combined, safe, combined.length);
        }
    }

    private static int indexOf(byte[] source, byte[] target) {
        outer : for (int i = 0; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++)
                if (source[i + j] != target[j])
                    continue outer;
            return i;
        }
        return -1;
    }

    public record FullMetadata(String replicationId, long baseOffset) {
    }

    public static final class SpoolLockedException extends IOException {
        public SpoolLockedException(String message) {
            super(message);
        }

        public SpoolLockedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
