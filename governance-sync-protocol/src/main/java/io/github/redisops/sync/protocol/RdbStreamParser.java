package io.github.redisops.sync.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Streaming Redis RDB reader. It preserves the encoded value and produces a DUMP-compatible payload,
 * avoiding materializing large collections. Unsupported/module encodings fail closed.
 */
public final class RdbStreamParser {
    private static final int TYPE_STRING = 0, TYPE_LIST = 1, TYPE_SET = 2, TYPE_ZSET = 3, TYPE_HASH = 4,
            TYPE_ZSET_2 = 5;
    private static final int TYPE_MODULE = 6, TYPE_MODULE_2 = 7, TYPE_HASH_ZIPMAP = 9, TYPE_LIST_ZIPLIST = 10,
            TYPE_SET_INTSET = 11, TYPE_ZSET_ZIPLIST = 12, TYPE_HASH_ZIPLIST = 13, TYPE_LIST_QUICKLIST = 14,
            TYPE_STREAM_LISTPACKS = 15, TYPE_HASH_LISTPACK = 16, TYPE_ZSET_LISTPACK = 17,
            TYPE_LIST_QUICKLIST_2 = 18, TYPE_STREAM_LISTPACKS_2 = 19, TYPE_SET_LISTPACK = 20,
            TYPE_STREAM_LISTPACKS_3 = 21, TYPE_HASH_METADATA_PRE_GA = 22, TYPE_HASH_LISTPACK_EX_PRE_GA = 23,
            TYPE_HASH_METADATA = 24, TYPE_HASH_LISTPACK_EX = 25;
    private static final int OPCODE_FUNCTION2 = 245, OPCODE_FUNCTION_PRE_GA = 246, OPCODE_MODULE_AUX = 247,
            OPCODE_IDLE = 248, OPCODE_FREQ = 249, OPCODE_AUX = 250, OPCODE_RESIZEDB = 251,
            OPCODE_EXPIRETIME_MS = 252, OPCODE_EXPIRETIME = 253, OPCODE_SELECTDB = 254, OPCODE_EOF = 255;

    private final InputStream input;
    private int rdbVersion, database;
    private long expireAt = -1;

    public RdbStreamParser(InputStream input) {
        this.input = new BufferedInputStream(input, 64 * 1024);
    }

    public void parse(Consumer<RdbEvent> consumer) throws IOException {
        byte[] header = readExact(9, null);
        String signature = new String(header, StandardCharsets.US_ASCII);
        if (!signature.startsWith("REDIS"))
            throw new RespProtocolException("invalid RDB signature");
        try {
            rdbVersion = Integer.parseInt(signature.substring(5));
        } catch (NumberFormatException e) {
            throw new RespProtocolException("invalid RDB version", e);
        }
        if (rdbVersion < 9 || rdbVersion > 13)
            throw new UnsupportedRdbTypeException(-1,
                    "RDB version " + rdbVersion + " is outside Redis 5.0-8.4 support");
        while (true) {
            int type = readUnsigned(null);
            switch (type) {
                case OPCODE_EOF -> {
                    readExact(8, null);
                    consumer.accept(RdbEvent.End.INSTANCE);
                    return;
                }
                case OPCODE_SELECTDB -> {
                    database = Math.toIntExact(readLength(null));
                    consumer.accept(new RdbEvent.SelectDb(database));
                }
                case OPCODE_EXPIRETIME_MS -> expireAt = readLittleEndianLong(null);
                case OPCODE_EXPIRETIME -> expireAt = readLittleEndianInt(null) * 1000L;
                case OPCODE_IDLE -> readLength(null);
                case OPCODE_FREQ -> readUnsigned(null);
                case OPCODE_AUX -> {
                    readString(null);
                    readString(null);
                }
                case OPCODE_RESIZEDB -> {
                    readLength(null);
                    readLength(null);
                }
                case OPCODE_FUNCTION2 -> consumer.accept(new RdbEvent.FunctionLibrary(readString(null)));
                case OPCODE_FUNCTION_PRE_GA ->
                    throw new UnsupportedRdbTypeException(type, "pre-GA Redis Functions encoding is not supported");
                case OPCODE_MODULE_AUX, TYPE_MODULE, TYPE_MODULE_2 ->
                    throw new UnsupportedRdbTypeException(type, "Redis Module data is not supported");
                default -> readKeyValue(type, consumer);
            }
        }
    }

    private void readKeyValue(int type, Consumer<RdbEvent> consumer) throws IOException {
        byte[] key = readString(null);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(type);
        skipObject(type, encoded);
        byte[] payload = toDumpPayload(encoded.toByteArray());
        consumer.accept(new RdbEvent.KeyValue(database, key, expireAt, payload));
        expireAt = -1;
    }

    private void skipObject(int type, OutputStream record) throws IOException {
        switch (type) {
            case TYPE_STRING -> readString(record);
            case TYPE_LIST, TYPE_SET -> {
                long count = readLength(record);
                repeat(count, () -> readString(record));
            }
            case TYPE_ZSET -> {
                long count = readLength(record);
                repeat(count, () -> {
                    readString(record);
                    readLegacyDouble(record);
                });
            }
            case TYPE_HASH -> {
                long count = readLength(record);
                repeat(count * 2, () -> readString(record));
            }
            case TYPE_ZSET_2 -> {
                long count = readLength(record);
                repeat(count, () -> {
                    readString(record);
                    readExact(8, record);
                });
            }
            case TYPE_HASH_ZIPMAP, TYPE_LIST_ZIPLIST, TYPE_SET_INTSET, TYPE_ZSET_ZIPLIST,
                    TYPE_HASH_ZIPLIST, TYPE_HASH_LISTPACK, TYPE_ZSET_LISTPACK, TYPE_SET_LISTPACK ->
                readString(record);
            case TYPE_LIST_QUICKLIST -> {
                long count = readLength(record);
                repeat(count, () -> readString(record));
            }
            case TYPE_LIST_QUICKLIST_2 -> {
                long count = readLength(record);
                repeat(count, () -> {
                    readLength(record);
                    readString(record);
                });
            }
            case TYPE_STREAM_LISTPACKS, TYPE_STREAM_LISTPACKS_2, TYPE_STREAM_LISTPACKS_3 ->
                throw new UnsupportedRdbTypeException(type,
                        "Stream RDB encoding requires the stream compatibility stage");
            case TYPE_HASH_METADATA_PRE_GA, TYPE_HASH_LISTPACK_EX_PRE_GA, TYPE_HASH_METADATA, TYPE_HASH_LISTPACK_EX ->
                throw new UnsupportedRdbTypeException(type,
                        "hash field expiration encoding requires Redis 7.4/8 fixture validation");
            case TYPE_MODULE, TYPE_MODULE_2 ->
                throw new UnsupportedRdbTypeException(type, "Redis Module data is not supported");
            default -> throw new UnsupportedRdbTypeException(type, "unknown RDB value type: " + type);
        }
    }

    private byte[] readString(OutputStream record) throws IOException {
        Length length = readLengthValue(record);
        if (!length.encoded)
            return readExact(Math.toIntExact(length.value), record);
        return switch ((int) length.value) {
            case 0 -> integerBytes((byte) readUnsigned(record));
            case 1 -> integerBytes((short) readLittleEndianShort(record));
            case 2 -> integerBytes(readLittleEndianInt(record));
            case 3 -> readLzf(record);
            default -> throw new RespProtocolException("unknown encoded RDB string: " + length.value);
        };
    }
    private byte[] readLzf(OutputStream record) throws IOException {
        long compressed = readLength(record), uncompressed = readLength(record);
        byte[] bytes = readExact(Math.toIntExact(compressed), record);
        return Lzf.decompress(bytes, Math.toIntExact(uncompressed));
    }
    private static byte[] integerBytes(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }

    private long readLength(OutputStream record) throws IOException {
        Length length = readLengthValue(record);
        if (length.encoded)
            throw new RespProtocolException("encoded value used where plain RDB length was expected");
        return length.value;
    }
    private Length readLengthValue(OutputStream record) throws IOException {
        int first = readUnsigned(record), kind = (first & 0xc0) >>> 6;
        return switch (kind) {
            case 0 -> new Length(first & 0x3f, false);
            case 1 -> new Length(((first & 0x3f) << 8) | readUnsigned(record), false);
            case 2 -> {
                int selector = first & 0x3f;
                if (selector == 0)
                    yield new Length(readBigEndianInt(record) & 0xffffffffL, false);
                if (selector == 1)
                    yield new Length(readBigEndianLong(record), false);
                throw new RespProtocolException("invalid RDB length selector: " + selector);
            }
            case 3 -> new Length(first & 0x3f, true);
            default -> throw new IllegalStateException();
        };
    }

    private void readLegacyDouble(OutputStream record) throws IOException {
        int length = readUnsigned(record);
        if (length == 253 || length == 254 || length == 255)
            return;
        readExact(length, record);
    }
    private byte[] toDumpPayload(byte[] object) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(object.length + 10);
        out.writeBytes(object);
        out.write(rdbVersion & 0xff);
        out.write((rdbVersion >>> 8) & 0xff);
        byte[] withoutCrc = out.toByteArray();
        long crc = RedisCrc64.calculate(withoutCrc);
        for (int i = 0; i < 8; i++)
            out.write((int) (crc >>> (8 * i)) & 0xff);
        return out.toByteArray();
    }
    private int readUnsigned(OutputStream record) throws IOException {
        int value = input.read();
        if (value < 0)
            throw new EOFException("truncated RDB");
        if (record != null)
            record.write(value);
        return value;
    }
    private byte[] readExact(int length, OutputStream record) throws IOException {
        if (length < 0)
            throw new RespProtocolException("negative RDB length");
        byte[] value = input.readNBytes(length);
        if (value.length != length)
            throw new EOFException("truncated RDB");
        if (record != null)
            record.write(value);
        return value;
    }
    private int readLittleEndianShort(OutputStream record) throws IOException {
        byte[] b = readExact(2, record);
        return (b[0] & 255) | ((b[1] & 255) << 8);
    }
    private int readLittleEndianInt(OutputStream record) throws IOException {
        byte[] b = readExact(4, record);
        return (b[0] & 255) | ((b[1] & 255) << 8) | ((b[2] & 255) << 16) | ((b[3] & 255) << 24);
    }
    private long readLittleEndianLong(OutputStream record) throws IOException {
        byte[] b = readExact(8, record);
        long value = 0;
        for (int i = 7; i >= 0; i--)
            value = (value << 8) | (b[i] & 255L);
        return value;
    }
    private int readBigEndianInt(OutputStream record) throws IOException {
        byte[] b = readExact(4, record);
        return ((b[0] & 255) << 24) | ((b[1] & 255) << 16) | ((b[2] & 255) << 8) | (b[3] & 255);
    }
    private long readBigEndianLong(OutputStream record) throws IOException {
        byte[] b = readExact(8, record);
        long value = 0;
        for (byte x : b)
            value = (value << 8) | (x & 255L);
        return value;
    }
    private void repeat(long count, IoAction action) throws IOException {
        if (count < 0 || count > Integer.MAX_VALUE)
            throw new RespProtocolException("unsafe RDB element count: " + count);
        for (long i = 0; i < count; i++)
            action.run();
    }
    private record Length(long value, boolean encoded) {
    }
    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private static final class Lzf {
        private static byte[] decompress(byte[] input, int outputLength) {
            byte[] output = new byte[outputLength];
            int in = 0, out = 0;
            while (in < input.length) {
                int control = input[in++] & 255;
                if (control < 32) {
                    int length = control + 1;
                    if (in + length > input.length || out + length > output.length)
                        throw new RespProtocolException("invalid LZF literal");
                    System.arraycopy(input, in, output, out, length);
                    in += length;
                    out += length;
                } else {
                    int length = control >>> 5, reference = out - ((control & 31) << 8) - 1;
                    if (length == 7) {
                        if (in >= input.length)
                            throw new RespProtocolException("invalid LZF length");
                        length += (input[in++] & 255);
                    }
                    if (in >= input.length)
                        throw new RespProtocolException("invalid LZF reference");
                    reference -= input[in++] & 255;
                    length += 2;
                    if (reference < 0 || out + length > output.length)
                        throw new RespProtocolException("invalid LZF back reference");
                    for (int i = 0; i < length; i++)
                        output[out++] = output[reference + i];
                }
            }
            if (out != outputLength)
                throw new RespProtocolException("LZF output length mismatch");
            return output;
        }
    }
}
