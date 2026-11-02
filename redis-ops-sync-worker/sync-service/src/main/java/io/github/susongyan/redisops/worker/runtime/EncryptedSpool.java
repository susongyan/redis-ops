package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.ReplicationCommand;
import io.github.susongyan.redisops.worker.protocol.RespCodec;
import io.github.susongyan.redisops.worker.protocol.RespProtocolException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongConsumer;

public final class EncryptedSpool implements AutoCloseable {
    // SunJCE's AES/GCM Cipher.update() buffers all input and emits nothing until doFinal, and its
    // internal buffer is copied on every append — Cipher{Input,Output}Stream over GCM therefore
    // degrades quadratically (20MB ≈ 55s, 237MB ≈ hours). The RDB stream is framed instead: each
    // chunk is sealed with its own GCM doFinal so the cipher never buffers across chunks.
    private static final byte[] RDB_MAGIC = {'R', 'S', 'P', '1'};
    private static final byte[] RDB_MAGIC_V2 = {'R', 'S', 'P', '2'};
    private static final byte[] RDB_MAGIC_PLAIN = {'R', 'S', 'P', '3'};
    private static final int RDB_CHUNK_PLAIN = 256 * 1024;
    private static final long LEGACY_RDB_ONESHOT_LIMIT = 1024L * 1024 * 1024;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RDB_ENCRYPTED_FILE = "full.rdb.enc";
    private static final String RDB_PLAIN_FILE = "full.rdb.plain";

    private final Path directory;
    private final long taskId;
    private final byte[] key;
    private final SpoolEncryption encryption;
    private final long segmentBytes;
    private final long limitBytes;
    private long commandRecordReadLimit = Long.MAX_VALUE;
    private DataOutputStream commandOutput;
    private FileOutputStream commandFile;
    private int segment;
    private int activeSegment = -1;
    private long segmentSize;
    private long bytes;
    private FileChannel lockChannel;
    private FileLock directoryLock;

    public EncryptedSpool(Path dataDirectory, long taskId, byte[] key, long segmentBytes, long limitBytes) {
        this(dataDirectory, taskId, null, key, SpoolEncryption.AES_GCM, segmentBytes, limitBytes);
    }

    public EncryptedSpool(Path dataDirectory, long taskId, String channel, byte[] key, long segmentBytes,
            long limitBytes) {
        this(dataDirectory, taskId, channel, key, SpoolEncryption.AES_GCM, segmentBytes, limitBytes);
    }

    public EncryptedSpool(Path dataDirectory, long taskId, byte[] key, SpoolEncryption encryption,
            long segmentBytes, long limitBytes) {
        this(dataDirectory, taskId, null, key, encryption, segmentBytes, limitBytes);
    }

    public EncryptedSpool(Path dataDirectory, long taskId, String channel, byte[] key, SpoolEncryption encryption,
            long segmentBytes, long limitBytes) {
        if (segmentBytes < 1024)
            throw new IllegalArgumentException("segmentBytes must be at least 1024");
        if (encryption == null)
            throw new IllegalArgumentException("spool encryption mode is required");
        if (key == null && encryption == SpoolEncryption.AES_GCM)
            throw new IllegalArgumentException("AES_GCM spool requires a key");
        Path taskDirectory = dataDirectory.resolve(Long.toString(taskId));
        if (channel != null) {
            if (channel.isBlank() || !channel.matches("[A-Za-z0-9._-]+"))
                throw new IllegalArgumentException("invalid spool channel");
            taskDirectory = taskDirectory.resolve(channel);
        }
        this.directory = taskDirectory;
        this.taskId = taskId;
        this.key = key == null ? null : key.clone();
        this.encryption = encryption;
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
        return writeRdb(source, length, eofMarker, ignored -> {
        });
    }

    public long writeRdb(InputStream source, long length, String eofMarker, LongConsumer progress)
            throws IOException {
        boolean plain = encryption == SpoolEncryption.NONE;
        Path target = directory.resolve(plain ? RDB_PLAIN_FILE : RDB_ENCRYPTED_FILE);
        Path temporary = directory.resolve((plain ? RDB_PLAIN_FILE : RDB_ENCRYPTED_FILE) + ".tmp");
        long plainBytes;
        try (FileOutputStream file = new FileOutputStream(temporary.toFile());
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file, 64 * 1024))) {
            out.write(plain ? RDB_MAGIC_PLAIN : RDB_MAGIC_V2);
            if (length >= 0)
                plainBytes = writeChunkedFixed(source, out, length, progress);
            else
                plainBytes = writeChunkedUntilMarker(source, out,
                        Objects.requireNonNull(eofMarker), progress);
            if (plain) {
                out.writeInt(0);
            } else {
                byte[] endIv = randomIv();
                out.writeInt(0);
                out.write(endIv);
                out.write(seal(endIv, aad("rdb2:end"), new byte[0]));
            }
            out.flush();
            file.getChannel().force(true);
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        bytes = directoryBytes();
        ensureLimit();
        return plainBytes;
    }

    /**
     * Opens the spooled RDB, detecting the on-disk format from the file itself: a plaintext spool
     * wins when present, otherwise the encrypted one is read (and authenticated) regardless of the
     * configured write mode, so recovery after a mode switch keeps working.
     */
    public InputStream openRdb() throws IOException {
        // Length-prefixed chunk framing reads directly off the channel stream; a BufferedInputStream
        // would only add a 64KB hop per 256KB chunk.
        Path plainFile = directory.resolve(RDB_PLAIN_FILE);
        if (Files.exists(plainFile)) {
            DataInputStream input = new DataInputStream(Files.newInputStream(plainFile));
            byte[] magic = input.readNBytes(RDB_MAGIC_PLAIN.length);
            if (!Arrays.equals(magic, RDB_MAGIC_PLAIN)) {
                input.close();
                throw new RespProtocolException("invalid plaintext RDB spool header");
            }
            return new ChunkedPlainInputStream(input);
        }
        DataInputStream input = new DataInputStream(Files.newInputStream(directory.resolve(RDB_ENCRYPTED_FILE)));
        byte[] magic = input.readNBytes(RDB_MAGIC_V2.length);
        if (Arrays.equals(magic, RDB_MAGIC_V2))
            return new ChunkedGcmInputStream(input);
        if (Arrays.equals(magic, RDB_MAGIC))
            return legacyOpenRdb(input);
        input.close();
        throw new RespProtocolException("invalid encrypted RDB spool header");
    }

    /** Pre-V2 spool: CipherInputStream degrades quadratically, so seal small files in one doFinal. */
    private InputStream legacyOpenRdb(DataInputStream input) throws IOException {
        byte[] iv = input.readNBytes(IV_BYTES);
        if (iv.length != IV_BYTES)
            throw new EOFException("truncated encrypted RDB spool header");
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, iv, aad("rdb"));
        long size = Files.size(directory.resolve("full.rdb.enc")) - IV_BYTES - RDB_MAGIC.length;
        if (size > LEGACY_RDB_ONESHOT_LIMIT)
            return new CipherInputStream(input, cipher);
        byte[] encrypted = input.readAllBytes();
        try {
            return new ByteArrayInputStream(cipher.doFinal(encrypted));
        } catch (GeneralSecurityException error) {
            throw new IOException("sync spool authentication failed", error);
        }
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
        long recordBytes = Integer.BYTES + plain.size()
                + (encryption == SpoolEncryption.NONE ? 0 : IV_BYTES + 16L);
        if (commandOutput == null || segmentSize > 0 && segmentSize + recordBytes > segmentBytes)
            rotate();
        if (encryption == SpoolEncryption.NONE) {
            commandOutput.writeInt(plain.size());
            commandOutput.write(plain.toByteArray());
        } else {
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
        }
        commandOutput.flush();
        commandFile.getChannel().force(false);
        segmentSize += recordBytes;
        bytes += recordBytes;
        ensureLimit();
    }

    public List<ReplicationCommand> commandsAfter(long appliedOffset) throws IOException {
        List<ReplicationCommand> result = new ArrayList<>();
        forEachCommandAfter(appliedOffset, result::add);
        return result;
    }

    public void limitCommandRecordReads(long maxBytes) {
        if (maxBytes < 1)
            throw new IllegalArgumentException("invalid spool record read limit");
        commandRecordReadLimit = maxBytes;
    }

    @FunctionalInterface
    public interface CommandConsumer {
        void accept(ReplicationCommand command) throws IOException;
    }

    /** Capture complete record boundaries under the append lock, then stream without retaining backlog. */
    public void forEachCommandAfter(long appliedOffset, CommandConsumer consumer) throws IOException {
        forEachCommandAfter(appliedOffset, limitBytes, consumer);
    }

    public void forEachCommandAfter(long appliedOffset, long maxRecordBytes, CommandConsumer consumer)
            throws IOException {
        record Segment(Path path, long size) {
        }
        List<Segment> snapshot = new ArrayList<>();
        synchronized (this) {
            for (Path path : existingSegments())
                snapshot.add(new Segment(path, Files.size(path)));
        }
        for (Segment segment : snapshot)
            readSegment(segment.path(), segment.size(), maxRecordBytes, command -> {
                if (command.endOffset() > appliedOffset)
                    consumer.accept(command);
            });
    }

    public synchronized void discardFullRdb() throws IOException {
        Files.deleteIfExists(directory.resolve(RDB_ENCRYPTED_FILE));
        Files.deleteIfExists(directory.resolve(RDB_ENCRYPTED_FILE + ".tmp"));
        Files.deleteIfExists(directory.resolve(RDB_PLAIN_FILE));
        Files.deleteIfExists(directory.resolve(RDB_PLAIN_FILE + ".tmp"));
        Files.deleteIfExists(directory.resolve("full.meta"));
        bytes = directoryBytes();
    }

    public synchronized void pruneCommandsThrough(long appliedOffset) throws IOException {
        for (Path path : existingSegments()) {
            if (segmentNumber(path) == activeSegment)
                continue;
            long[] lastOffset = {-1};
            readSegment(path, Files.size(path), limitBytes, command -> lastOffset[0] = command.endOffset());
            if (lastOffset[0] <= appliedOffset)
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
        if (key != null)
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
            if (!(value instanceof io.github.susongyan.redisops.worker.protocol.RespValue.Array array))
                throw new RespProtocolException("spooled command is not an array");
            List<byte[]> arguments = new ArrayList<>();
            for (var item : array.values()) {
                if (!(item instanceof io.github.susongyan.redisops.worker.protocol.RespValue.Bulk bulk))
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
        boolean plain = encryption == SpoolEncryption.NONE;
        Path path = segmentPath(activeSegment);
        commandFile = new FileOutputStream(path.toFile(), true);
        commandOutput = new DataOutputStream(new BufferedOutputStream(commandFile, 64 * 1024));
        // Plain segments carry the magic at the file head so reads self-identify the record format;
        // a rotated segment is always a fresh file, so the header is written exactly once.
        if (plain && Files.size(path) == 0) {
            commandOutput.write(RDB_MAGIC_PLAIN);
            segmentSize = RDB_MAGIC_PLAIN.length;
        } else {
            segmentSize = Files.size(path);
        }
    }

    private void readSegment(Path path, long remaining, long maxRecordBytes, CommandConsumer consumer)
            throws IOException {
        boolean plainSegment = path.getFileName().toString().endsWith(".pseg");
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (plainSegment) {
                byte[] magic = input.readNBytes(RDB_MAGIC_PLAIN.length);
                if (!Arrays.equals(magic, RDB_MAGIC_PLAIN))
                    throw new RespProtocolException("invalid plaintext spool segment header");
                remaining -= RDB_MAGIC_PLAIN.length;
            }
            int fileSegment = segmentNumber(path);
            while (remaining > 0) {
                if (remaining < Integer.BYTES)
                    throw new EOFException("truncated spool record header");
                int length = input.readInt();
                remaining -= Integer.BYTES;
                if (plainSegment) {
                    if (length < 1 || length > limitBytes || length > maxRecordBytes
                            || length > commandRecordReadLimit || length > remaining)
                        throw new RespProtocolException("invalid plaintext spool record length");
                    byte[] plainRecord = input.readNBytes(length);
                    if (plainRecord.length != length)
                        throw new EOFException("truncated plaintext spool record");
                    consumer.accept(decodeCommand(plainRecord));
                    remaining -= length;
                    continue;
                }
                if (length < IV_BYTES + 16 || length > limitBytes || length > maxRecordBytes
                        || length > commandRecordReadLimit || length > remaining)
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
                consumer.accept(decodeCommand(plain));
                remaining -= length;
            }
        }
    }

    private List<Path> existingSegments() throws IOException {
        if (!Files.exists(directory))
            return List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(x -> x.getFileName().toString().matches("commands-\\d{8}\\.p?seg"))
                    .sorted().toList();
        }
    }

    private Path segmentPath(int number) {
        String extension = encryption == SpoolEncryption.NONE ? "pseg" : "seg";
        return directory.resolve("commands-%08d.%s".formatted(number, extension));
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
        if (key == null)
            throw new IllegalStateException("an encrypted spool exists but no spool key is available"
                    + " (configure REDIS_OPS_CREDENTIAL_KEYS or write with spool-encryption=NONE)");
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

    private byte[] seal(byte[] iv, byte[] aad, byte[] plain) throws IOException {
        return seal(iv, aad, plain, plain.length);
    }

    /** Encrypts plain[0..length) without copying when the caller passes the whole buffer. */
    private byte[] seal(byte[] iv, byte[] aad, byte[] plain, int length) throws IOException {
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv, aad);
            return length == plain.length ? cipher.doFinal(plain) : cipher.doFinal(plain, 0, length);
        } catch (GeneralSecurityException error) {
            throw new IOException("cannot encrypt sync spool chunk", error);
        }
    }

    private long writeChunkedFixed(InputStream source, DataOutputStream out, long length, LongConsumer progress)
            throws IOException {
        byte[] chunk = new byte[RDB_CHUNK_PLAIN];
        long remaining = length;
        while (remaining > 0) {
            int read = source.readNBytes(chunk, 0, (int) Math.min(chunk.length, remaining));
            if (read == 0)
                throw new EOFException("truncated fixed-length RDB transfer");
            writeChunk(out, chunk, read);
            remaining -= read;
            progress.accept(length - remaining);
        }
        return length;
    }

    private long writeChunkedUntilMarker(InputStream source, DataOutputStream out, String markerValue,
            LongConsumer progress) throws IOException {
        if (!(source instanceof PushbackInputStream input))
            throw new IllegalArgumentException("diskless RDB source must support pushback");
        byte[] marker = markerValue.getBytes(StandardCharsets.US_ASCII);
        byte[] tail = new byte[0];
        byte[] buffer = new byte[RDB_CHUNK_PLAIN];
        long written = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0)
                throw new EOFException("diskless RDB EOF marker was not received");
            byte[] combined = new byte[tail.length + read];
            System.arraycopy(tail, 0, combined, 0, tail.length);
            System.arraycopy(buffer, 0, combined, tail.length, read);
            int markerAt = indexOf(combined, marker);
            if (markerAt >= 0) {
                if (markerAt > 0)
                    writeChunk(out, combined, markerAt);
                written += markerAt;
                progress.accept(written);
                int after = combined.length - markerAt - marker.length;
                if (after > 0)
                    input.unread(combined, markerAt + marker.length, after);
                return written;
            }
            int safe = Math.max(0, combined.length - marker.length + 1);
            if (safe > 0)
                writeChunk(out, combined, safe);
            written += safe;
            progress.accept(written);
            tail = Arrays.copyOfRange(combined, safe, combined.length);
        }
    }

    private void writeChunk(DataOutputStream out, byte[] plain, int length) throws IOException {
        if (encryption == SpoolEncryption.NONE) {
            out.writeInt(length);
            out.write(plain, 0, length);
            return;
        }
        byte[] iv = randomIv();
        byte[] encrypted = seal(iv, aad("rdb2"), plain, length);
        out.writeInt(encrypted.length);
        out.write(iv);
        out.write(encrypted);
    }

    /** Serves decrypted plaintext chunk by chunk; an int 0 record carries the sealed end marker. */
    private final class ChunkedGcmInputStream extends InputStream {
        private final DataInputStream in;
        private byte[] pending = new byte[0];
        private int position;
        private boolean endReached;

        private ChunkedGcmInputStream(DataInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            while (position >= pending.length) {
                if (endReached || !fillNextChunk())
                    return -1;
            }
            int count = Math.min(length, pending.length - position);
            System.arraycopy(pending, position, target, offset, count);
            position += count;
            return count;
        }

        private boolean fillNextChunk() throws IOException {
            int encryptedLength;
            try {
                encryptedLength = in.readInt();
            } catch (EOFException error) {
                throw new EOFException("truncated encrypted RDB spool: end marker missing");
            }
            if (encryptedLength == 0) {
                verifyEndMarker();
                endReached = true;
                return false;
            }
            if (encryptedLength < 16 || encryptedLength > RDB_CHUNK_PLAIN + 16)
                throw new RespProtocolException("invalid encrypted RDB spool chunk length");
            byte[] iv = in.readNBytes(IV_BYTES);
            byte[] encrypted = in.readNBytes(encryptedLength);
            if (iv.length != IV_BYTES || encrypted.length != encryptedLength)
                throw new EOFException("truncated encrypted RDB spool chunk");
            try {
                pending = cipher(Cipher.DECRYPT_MODE, iv, aad("rdb2")).doFinal(encrypted);
            } catch (GeneralSecurityException error) {
                throw new IOException("sync spool authentication failed", error);
            }
            position = 0;
            return true;
        }

        private void verifyEndMarker() throws IOException {
            byte[] iv = in.readNBytes(IV_BYTES);
            byte[] tag = in.readNBytes(16);
            if (iv.length != IV_BYTES || tag.length != 16)
                throw new EOFException("truncated encrypted RDB spool end marker");
            try {
                cipher(Cipher.DECRYPT_MODE, iv, aad("rdb2:end")).doFinal(tag);
            } catch (GeneralSecurityException error) {
                throw new IOException("sync spool authentication failed", error);
            }
        }
    }

    /** Serves plaintext chunks of a NONE-mode spool; the framing mirrors the GCM stream minus crypto. */
    private static final class ChunkedPlainInputStream extends InputStream {
        private final DataInputStream in;
        private byte[] pending = new byte[0];
        private int position;
        private boolean endReached;

        private ChunkedPlainInputStream(DataInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            while (position >= pending.length) {
                if (endReached || !fillNextChunk())
                    return -1;
            }
            int count = Math.min(length, pending.length - position);
            System.arraycopy(pending, position, target, offset, count);
            position += count;
            return count;
        }

        private boolean fillNextChunk() throws IOException {
            int chunkLength;
            try {
                chunkLength = in.readInt();
            } catch (EOFException error) {
                throw new EOFException("truncated plaintext RDB spool: end marker missing");
            }
            if (chunkLength == 0) {
                endReached = true;
                return false;
            }
            if (chunkLength < 1 || chunkLength > RDB_CHUNK_PLAIN)
                throw new RespProtocolException("invalid plaintext RDB spool chunk length");
            byte[] chunk = in.readNBytes(chunkLength);
            if (chunk.length != chunkLength)
                throw new EOFException("truncated plaintext RDB spool chunk");
            pending = chunk;
            position = 0;
            return true;
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
