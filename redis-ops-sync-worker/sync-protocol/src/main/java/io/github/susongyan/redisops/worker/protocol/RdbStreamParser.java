package io.github.susongyan.redisops.worker.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Streaming Redis RDB reader. It preserves the encoded value and produces a DUMP-compatible payload,
 * avoiding materializing large collections. Unsupported/module encodings fail closed.
 *
 * <p>
 * Split mode ({@link #parse(Consumer, SplitOptions)}) additionally routes values by size: at or
 * below the RESTORE threshold nothing changes; splittable values between threshold and hard cap are
 * re-emitted as element streams ({@code BigKeyValueBegin}/{@code KeyElement}/{@code BigKeyValueEnd});
 * anything above the hard cap, or an unsupported type the operator opted into skipping, arrives as
 * {@code SkippedKey} after its bytes have been consumed. Values are recorded into a growable buffer
 * only up to the crossing element, so peak heap stays O(threshold), never O(key).
 */
public final class RdbStreamParser {
    static final int TYPE_STRING = 0, TYPE_LIST = 1, TYPE_SET = 2, TYPE_ZSET = 3, TYPE_HASH = 4,
            TYPE_ZSET_2 = 5;
    static final int TYPE_MODULE = 6, TYPE_MODULE_2 = 7, TYPE_HASH_ZIPMAP = 9, TYPE_LIST_ZIPLIST = 10,
            TYPE_SET_INTSET = 11, TYPE_ZSET_ZIPLIST = 12, TYPE_HASH_ZIPLIST = 13, TYPE_LIST_QUICKLIST = 14,
            TYPE_STREAM_LISTPACKS = 15, TYPE_HASH_LISTPACK = 16, TYPE_ZSET_LISTPACK = 17,
            TYPE_LIST_QUICKLIST_2 = 18, TYPE_STREAM_LISTPACKS_2 = 19, TYPE_SET_LISTPACK = 20,
            TYPE_STREAM_LISTPACKS_3 = 21, TYPE_HASH_METADATA_PRE_GA = 22, TYPE_HASH_LISTPACK_EX_PRE_GA = 23,
            TYPE_HASH_METADATA = 24, TYPE_HASH_LISTPACK_EX = 25;
    private static final int OPCODE_SLOT_INFO = 244, OPCODE_FUNCTION2 = 245, OPCODE_FUNCTION_PRE_GA = 246,
            OPCODE_MODULE_AUX = 247, OPCODE_IDLE = 248, OPCODE_FREQ = 249, OPCODE_AUX = 250,
            OPCODE_RESIZEDB = 251, OPCODE_EXPIRETIME_MS = 252, OPCODE_EXPIRETIME = 253,
            OPCODE_SELECTDB = 254, OPCODE_EOF = 255;
    private static final int MODULE_OPCODE_EOF = 0, MODULE_OPCODE_SINT = 1, MODULE_OPCODE_UINT = 2,
            MODULE_OPCODE_FLOAT = 3, MODULE_OPCODE_DOUBLE = 4, MODULE_OPCODE_STRING = 5;
    private static final byte[] SCORE_NEG_INF = "-inf".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SCORE_POS_INF = "+inf".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SCORE_NAN = "nan".getBytes(StandardCharsets.US_ASCII);

    private final InputStream input;
    private long decodeFallbacks;

    /**
     * Times a value over the split threshold could not be decoded and was re-emitted as a plain
     * {@code KeyValue} for the RESTORE path (LZF, compact-blob or prefix decode failure). The
     * sync runner polls this to feed {@code sync_split_fallback_total}.
     */
    public long decodeFallbacks() {
        return decodeFallbacks;
    }

    private final byte[] transferBuffer = new byte[8 * 1024];
    private int rdbVersion, database;
    private long expireAt = -1;
    private long crc;
    private long position;

    public RdbStreamParser(InputStream input) {
        this.input = new BufferedInputStream(input, 64 * 1024);
    }

    public long position() {
        return position;
    }

    public void parse(Consumer<RdbEvent> consumer) throws IOException {
        parse(consumer, null);
    }

    public void parse(Consumer<RdbEvent> consumer, SplitOptions options) throws IOException {
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
                    byte[] checksum = readExactRaw(8);
                    long expected = littleEndianLong(checksum);
                    if (expected != 0 && expected != crc)
                        throw new RespProtocolException("RDB checksum mismatch");
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
                case OPCODE_SLOT_INFO -> {
                    readLength(null); // slot ID
                    readLength(null); // key count
                    readLength(null); // expiring key count
                }
                case OPCODE_FUNCTION2 -> consumer.accept(new RdbEvent.FunctionLibrary(readString(null)));
                case OPCODE_FUNCTION_PRE_GA ->
                    throw new UnsupportedRdbTypeException(type, "pre-GA Redis Functions encoding is not supported");
                case OPCODE_MODULE_AUX, TYPE_MODULE ->
                    throw new UnsupportedRdbTypeException(type, "Redis Module data is not supported");
                default -> readKeyValue(type, consumer, options);
            }
        }
    }

    private void readKeyValue(int type, Consumer<RdbEvent> consumer, SplitOptions options) throws IOException {
        byte[] key = readString(null);
        long valueStart = position;
        try {
            if (options == null)
                legacyValue(type, consumer, key);
            else
                splitValue(type, consumer, options, key, valueStart);
        } finally {
            expireAt = -1;
        }
    }

    private void legacyValue(int type, Consumer<RdbEvent> consumer, byte[] key) throws IOException {
        if (type == TYPE_MODULE_2)
            throw new UnsupportedRdbTypeException(type, "Redis Module data is not supported");
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(type);
        skipObject(type, encoded);
        byte[] payload = toDumpPayload(encoded.toByteArray());
        consumer.accept(new RdbEvent.KeyValue(database, key, expireAt, payload));
    }

    private void splitValue(int type, Consumer<RdbEvent> consumer, SplitOptions options, byte[] key,
            long valueStart) throws IOException {
        if (type == TYPE_MODULE_2) {
            if (!options.skipUnsupportedTypes())
                throw new UnsupportedRdbTypeException(type, "Redis Module data is not supported");
            skipModule2Payload();
            consumer.accept(new RdbEvent.SkippedKey(key, type, position - valueStart,
                    RdbEvent.SkipReason.UNSUPPORTED_TYPE, 0));
            return;
        }
        if (type == TYPE_MODULE)
            throw new UnsupportedRdbTypeException(type, "Redis Module data is not supported");
        switch (type) {
            case TYPE_STRING, TYPE_HASH_ZIPMAP, TYPE_LIST_ZIPLIST, TYPE_SET_INTSET, TYPE_ZSET_ZIPLIST,
                    TYPE_HASH_ZIPLIST, TYPE_HASH_LISTPACK, TYPE_ZSET_LISTPACK, TYPE_SET_LISTPACK ->
                readBlobValue(type, consumer, options, key, valueStart);
            case TYPE_LIST, TYPE_SET, TYPE_ZSET, TYPE_HASH, TYPE_ZSET_2 ->
                readElementValue(type, consumer, options, key, valueStart);
            case TYPE_LIST_QUICKLIST, TYPE_LIST_QUICKLIST_2 ->
                readQuicklistValue(type, consumer, options, key, valueStart);
            case TYPE_STREAM_LISTPACKS, TYPE_STREAM_LISTPACKS_2, TYPE_STREAM_LISTPACKS_3, TYPE_HASH_METADATA,
                    TYPE_HASH_LISTPACK_EX ->
                readCappedValue(type, consumer, key, options, valueStart);
            case TYPE_HASH_METADATA_PRE_GA, TYPE_HASH_LISTPACK_EX_PRE_GA ->
                throw new UnsupportedRdbTypeException(type, "pre-GA hash field expiration encoding is not supported");
            default -> throw new UnsupportedRdbTypeException(type, "unknown RDB value type: " + type);
        }
    }

    /** Types the split path can rebuild with element commands; everything else falls back to RESTORE. */
    static boolean splittableType(int type) {
        return switch (type) {
            case TYPE_LIST, TYPE_SET, TYPE_ZSET, TYPE_HASH, TYPE_ZSET_2, TYPE_HASH_ZIPMAP, TYPE_LIST_ZIPLIST,
                    TYPE_SET_INTSET, TYPE_ZSET_ZIPLIST, TYPE_HASH_ZIPLIST, TYPE_HASH_LISTPACK,
                    TYPE_ZSET_LISTPACK, TYPE_LIST_QUICKLIST, TYPE_LIST_QUICKLIST_2, TYPE_SET_LISTPACK ->
                true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Split mode: single length-prefixed blobs (string + compact container encodings)
    // ---------------------------------------------------------------------------------------------

    private void readBlobValue(int type, Consumer<RdbEvent> consumer, SplitOptions options, byte[] key,
            long valueStart) throws IOException {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(type);
        Length length = readLengthValue(record);
        if (length.encoded && length.value == 3) { // LZF blob; the wire size is the compressed size
            long compressed = readLength(record);
            long uncompressed = readLength(record);
            if (compressed > options.hardCapBytes()) {
                transferBytes(compressed, null);
                consumer.accept(skipped(key, type, valueStart, RdbEvent.SkipReason.OVER_HARD_CAP, 0));
                return;
            }
            byte[] blob = readExact(Math.toIntExact(compressed), record);
            if (type == TYPE_STRING || !splittableType(type) || compressed <= options.maxRestoreBytes()) {
                acceptKeyValue(consumer, key, record.toByteArray());
                return;
            }
            byte[] plain;
            try {
                plain = Lzf.decompress(blob, Math.toIntExact(uncompressed));
            } catch (RuntimeException e) { // undecompressable here — let the target judge the wire form
                decodeFallbacks++;
                acceptKeyValue(consumer, key, record.toByteArray());
                return;
            }
            emitDecodedBlob(type, key, plain, record, consumer, position - valueStart);
            return;
        }
        if (!length.encoded) {
            long len = length.value;
            if (len > options.hardCapBytes()) {
                transferBytes(len, null);
                consumer.accept(skipped(key, type, valueStart, RdbEvent.SkipReason.OVER_HARD_CAP, 0));
                return;
            }
            if (type == TYPE_STRING || !splittableType(type) || len <= options.maxRestoreBytes()) {
                transferBytes(len, record);
                acceptKeyValue(consumer, key, record.toByteArray());
                return;
            }
            byte[] blob = readExact(Math.toIntExact(len), record);
            emitDecodedBlob(type, key, blob, record, consumer, len);
            return;
        }
        switch ((int) length.value) { // int-encoded string: tiny by construction
            case 0 -> transferBytes(1, record);
            case 1 -> transferBytes(2, record);
            case 2 -> transferBytes(4, record);
            default -> throw new RespProtocolException("unknown encoded RDB string: " + length.value);
        }
        acceptKeyValue(consumer, key, record.toByteArray());
    }

    private void emitDecodedBlob(int type, byte[] key, byte[] plain, ByteArrayOutputStream record,
            Consumer<RdbEvent> consumer, long payloadBytes) {
        int arity = arityOf(type);
        List<byte[]> entries;
        try {
            entries = RdbValueDecoder.decode(type, plain);
            if (arity == 2 && entries.size() % 2 != 0)
                throw new RespProtocolException("odd entry count for paired compact encoding");
        } catch (RuntimeException e) { // decoder disagrees with the blob — fall back to RESTORE
            decodeFallbacks++;
            acceptKeyValue(consumer, key, record.toByteArray());
            return;
        }
        consumer.accept(new RdbEvent.BigKeyValueBegin(database, key, expireAt, type, entries.size() / arity));
        for (int i = 0; i < entries.size(); i += arity) {
            if (arity == 1)
                consumer.accept(new RdbEvent.KeyElement(entries.get(i), null, null));
            else if (type == TYPE_ZSET_ZIPLIST || type == TYPE_ZSET_LISTPACK)
                consumer.accept(new RdbEvent.KeyElement(entries.get(i), null, entries.get(i + 1)));
            else
                consumer.accept(new RdbEvent.KeyElement(entries.get(i), entries.get(i + 1), null));
        }
        consumer.accept(new RdbEvent.BigKeyValueEnd(key, payloadBytes, entries.size() / arity));
    }

    // ---------------------------------------------------------------------------------------------
    // Split mode: plain element-wise collections (list / set / zset / hash)
    // ---------------------------------------------------------------------------------------------

    private void readElementValue(int type, Consumer<RdbEvent> consumer, SplitOptions options, byte[] key,
            long valueStart) throws IOException {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(type);
        long count = readLength(record);
        int arity = arityOf(type);
        boolean scored = type == TYPE_ZSET || type == TYPE_ZSET_2;
        long i = 0;
        boolean oversize = false;
        for (; i < count; i++) { // phase 1: record without materializing any element
            transferElement(type, arity, scored, record);
            if (record.size() > options.hardCapBytes()) {
                oversize = true;
                break;
            }
            if (record.size() > options.maxRestoreBytes())
                break;
        }
        if (oversize) {
            discardElements(type, arity, scored, count - i - 1);
            consumer.accept(skipped(key, type, valueStart, RdbEvent.SkipReason.OVER_HARD_CAP, count));
            return;
        }
        if (i == count) { // never crossed the threshold — same as legacy
            acceptKeyValue(consumer, key, record.toByteArray());
            return;
        }
        long recordedElements = i + 1;
        List<RdbEvent.KeyElement> prefix = null;
        try { // phase 2a: recover the recorded elements from the buffer copy
            prefix = PrefixReader.readElements(record.toByteArray(), type, recordedElements);
        } catch (RuntimeException e) { // prefix decode failure before any event — fall back to RESTORE
            decodeFallbacks++;
        }
        if (prefix == null) {
            for (long j = recordedElements; j < count; j++) {
                transferElement(type, arity, scored, record);
                if (record.size() > options.hardCapBytes()) {
                    discardElements(type, arity, scored, count - j - 1);
                    consumer.accept(skipped(key, type, valueStart, RdbEvent.SkipReason.OVER_HARD_CAP, count));
                    return;
                }
            }
            acceptKeyValue(consumer, key, record.toByteArray());
            return;
        }
        consumer.accept(new RdbEvent.BigKeyValueBegin(database, key, expireAt, type, count));
        long emitted = 0;
        for (RdbEvent.KeyElement element : prefix) {
            consumer.accept(element);
            emitted++;
        }
        for (long j = recordedElements; j < count; j++) { // phase 2b: stream the remainder unrecorded
            RdbEvent.KeyElement element = readElementStreaming(type, arity);
            if (position - valueStart > options.hardCapBytes()) {
                discardElements(type, arity, scored, count - j - 1);
                consumer.accept(skipped(key, type, valueStart, RdbEvent.SkipReason.OVER_HARD_CAP, count));
                return;
            }
            consumer.accept(element);
            emitted++;
        }
        consumer.accept(new RdbEvent.BigKeyValueEnd(key, position - valueStart, emitted));
    }

    // ---------------------------------------------------------------------------------------------
    // Split mode: quicklists (type 14 = ziplist nodes, type 18 = listpack/plain nodes)
    // ---------------------------------------------------------------------------------------------

    private void readQuicklistValue(int type, Consumer<RdbEvent> consumer, SplitOptions options, byte[] key,
            long valueStart) throws IOException {
        boolean v2 = type == TYPE_LIST_QUICKLIST_2;
        boolean listpack = v2; // v1 nodes are ziplists, v2 packed nodes are listpacks
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(type);
        long nodeCount = readLength(record);
        long i = 0;
        boolean oversize = false;
        for (; i < nodeCount; i++) { // phase 1: record node blobs whole
            if (v2)
                readContainer(record);
            transferString(record);
            if (record.size() > options.hardCapBytes()) {
                oversize = true;
                break;
            }
            if (record.size() > options.maxRestoreBytes())
                break;
        }
        if (oversize) {
            long remaining = discardQuicklistNodes(v2, listpack, nodeCount - i - 1);
            long recorded = recordedQuicklistElements(record, type, i + 1);
            consumer.accept(skipped(key, type, valueStart, RdbEvent.SkipReason.OVER_HARD_CAP,
                    remaining < 0 || recorded < 0 ? 0 : remaining + recorded));
            return;
        }
        if (i == nodeCount) {
            acceptKeyValue(consumer, key, record.toByteArray());
            return;
        }
        long recordedNodes = i + 1;
        List<byte[]> prefixElements = null;
        try { // phase 2a: recover node blobs and decode them from the buffer copy
            List<QuicklistNode> nodes = PrefixReader.readQuicklistNodes(record.toByteArray(), type, recordedNodes);
            prefixElements = new ArrayList<>();
            for (QuicklistNode node : nodes)
                prefixElements.addAll(decodeQuicklistNode(type, node));
        } catch (RuntimeException e) { // prefix decode failure before any event — fall back to RESTORE
            decodeFallbacks++;
        }
        if (prefixElements == null) {
            for (long j = recordedNodes; j < nodeCount; j++) {
                if (v2)
                    readContainer(record);
                transferString(record);
                if (record.size() > options.hardCapBytes()) {
                    long remaining = discardQuicklistNodes(v2, listpack, nodeCount - j - 1);
                    long recorded = recordedQuicklistElements(record, type, j + 1);
                    consumer.accept(skipped(key, type, valueStart, RdbEvent.SkipReason.OVER_HARD_CAP,
                            remaining < 0 || recorded < 0 ? 0 : remaining + recorded));
                    return;
                }
            }
            acceptKeyValue(consumer, key, record.toByteArray());
            return;
        }
        consumer.accept(new RdbEvent.BigKeyValueBegin(database, key, expireAt, type, nodeCount));
        long emitted = 0;
        for (byte[] element : prefixElements) {
            consumer.accept(new RdbEvent.KeyElement(element, null, null));
            emitted++;
        }
        for (long j = recordedNodes; j < nodeCount; j++) { // phase 2b: stream the remaining nodes
            int container = v2 ? (int) readContainer(null) : 1;
            byte[] blob = readString(null);
            List<byte[]> elements = decodeQuicklistNode(type, new QuicklistNode(container, blob));
            if (position - valueStart > options.hardCapBytes()) {
                long remaining = discardQuicklistNodes(v2, listpack, nodeCount - j - 1);
                consumer.accept(skipped(key, type, valueStart, RdbEvent.SkipReason.OVER_HARD_CAP,
                        remaining < 0 ? 0 : emitted + elements.size() + remaining));
                return;
            }
            for (byte[] element : elements) {
                consumer.accept(new RdbEvent.KeyElement(element, null, null));
                emitted++;
            }
        }
        consumer.accept(new RdbEvent.BigKeyValueEnd(key, position - valueStart, emitted));
    }

    private long readContainer(OutputStream record) throws IOException {
        long container = readLength(record);
        if (container != 1 && container != 2)
            throw new RespProtocolException("invalid quicklist node container: " + container);
        return container;
    }

    private List<byte[]> decodeQuicklistNode(int type, QuicklistNode node) {
        if (node.container() == 2) // plain node: the blob is one element
            return List.of(node.blob());
        return type == TYPE_LIST_QUICKLIST_2
                ? RdbValueDecoder.listpackEntries(node.blob())
                : RdbValueDecoder.ziplistEntries(node.blob());
    }

    // ---------------------------------------------------------------------------------------------
    // Split mode: values that can only be RESTOREd (streams, field-TTL hashes) under a hard cap
    // ---------------------------------------------------------------------------------------------

    private void readCappedValue(int type, Consumer<RdbEvent> consumer, byte[] key, SplitOptions options,
            long valueStart) throws IOException {
        CappedRecord record = new CappedRecord(options.hardCapBytes());
        record.write(type);
        skipObject(type, record);
        if (record.overflow)
            consumer.accept(skipped(key, type, valueStart, RdbEvent.SkipReason.OVER_HARD_CAP, 0));
        else
            acceptKeyValue(consumer, key, record.bytes());
    }

    /** Skips a MODULE_2 value: length-encoded module id, then the opcode envelope until EOF. */
    private void skipModule2Payload() throws IOException {
        readLength(null); // module id
        while (true) {
            long opcode = readLength(null);
            switch ((int) opcode) {
                case MODULE_OPCODE_EOF -> {
                    return;
                }
                case MODULE_OPCODE_SINT, MODULE_OPCODE_UINT -> readLength(null);
                case MODULE_OPCODE_FLOAT -> transferBytes(4, null);
                case MODULE_OPCODE_DOUBLE -> transferBytes(8, null);
                case MODULE_OPCODE_STRING -> transferString(null);
                default -> throw new RespProtocolException("unknown module opcode " + opcode);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Split-mode plumbing
    // ---------------------------------------------------------------------------------------------

    private void acceptKeyValue(Consumer<RdbEvent> consumer, byte[] key, byte[] encoded) {
        consumer.accept(new RdbEvent.KeyValue(database, key, expireAt, toDumpPayload(encoded)));
    }

    private RdbEvent.SkippedKey skipped(byte[] key, int type, long valueStart, RdbEvent.SkipReason reason,
            long estimatedElements) {
        return new RdbEvent.SkippedKey(key, type, position - valueStart, reason, estimatedElements);
    }

    private static int arityOf(int type) {
        return type == TYPE_HASH ? 2 : 1;
    }

    private void transferElement(int type, int arity, boolean scored, OutputStream record) throws IOException {
        for (int a = 0; a < arity; a++)
            transferString(record);
        if (scored)
            transferScore(type, record);
    }

    private void discardElements(int type, int arity, boolean scored, long count) throws IOException {
        for (long i = 0; i < count; i++)
            transferElement(type, arity, scored, null);
    }

    /**
     * Discards {@code count} quicklist nodes while summing their declared entry counts (ziplist
     * {@code zllen} / listpack header count; a plain node holds exactly one element). The RDB has
     * no whole-list total, so this header sum is the exact count. Every node is consumed in full —
     * the skip must leave the stream (and its running CRC) exactly aligned — even once the sum
     * turns unknowable; LZF-compressed nodes are inflated only far enough to read their header.
     * Returns {@code -1} when any node is unknowable: a header prefix that will not inflate, a
     * 0xFFFF header count (only a full scan would know), an integer-encoded blob, or a node
     * shorter than its own header.
     */
    private long discardQuicklistNodes(boolean v2, boolean listpack, long count) throws IOException {
        long elements = 0;
        boolean knowable = true;
        int offset = listpack ? 4 : 8;
        for (long i = 0; i < count; i++) {
            if (v2 && readContainer(null) == 2) {
                transferString(null);
                elements++;
                continue;
            }
            Length length = readLengthValue(null);
            long declared;
            if (!length.encoded) {
                int peek = (int) Math.min(length.value, offset + 2);
                declared = declaredEntries(readExact(peek, null), offset);
                transferBytes(length.value - peek, null);
            } else {
                declared = switch ((int) length.value) {
                    case 3 -> { // LZF blob: consume it whole, inflate only the header prefix
                        long compressed = readLength(null);
                        readLength(null); // uncompressed size — only the header prefix is inflated
                        yield lzfDeclaredEntries(readExact(Math.toIntExact(compressed), null), offset);
                    }
                    case 0, 1, 2 -> { // integer-encoded blob: 1-4 payload bytes, no node header
                        transferBytes(1L << (int) length.value, null);
                        yield -1;
                    }
                    default -> throw new RespProtocolException("unknown encoded RDB string: " + length.value);
                };
            }
            if (declared < 0)
                knowable = false;
            else
                elements += declared;
        }
        return knowable ? elements : -1;
    }

    /** Sums the declared entry counts of node blobs already recorded in the phase-1 buffer. */
    private long recordedQuicklistElements(ByteArrayOutputStream record, int type, long nodes) {
        try {
            boolean listpack = type == TYPE_LIST_QUICKLIST_2;
            long sum = 0;
            for (QuicklistNode node : PrefixReader.readQuicklistNodes(record.toByteArray(), type, nodes)) {
                if (node.container() == 2) {
                    sum++;
                    continue;
                }
                byte[] blob = node.blob();
                int offset = listpack ? 4 : 8;
                long declared = declaredEntries(blob, offset);
                if (declared < 0)
                    return -1;
                sum += declared;
            }
            return sum;
        } catch (RuntimeException e) {
            return -1; // buffer disagrees with the node framing: treat the total as unknowable
        }
    }

    /** Declared entry count from a node blob's header bytes, or -1 when it cannot be trusted. */
    private static long declaredEntries(byte[] header, int offset) {
        if (header.length < offset + 2)
            return -1; // shorter than its own header: refuse to guess
        long declared = (header[offset] & 255L) | ((header[offset + 1] & 255L) << 8);
        return declared == 0xFFFF ? -1 : declared; // 0xFFFF = only a full scan would know
    }

    /**
     * Declared entry count of an LZF-compressed node blob, or -1 when the header prefix cannot
     * be inflated. Only the first {@code offset + 2} decompressed bytes are needed, so the cost
     * stays bounded no matter how large the node is.
     */
    private static long lzfDeclaredEntries(byte[] compressed, int offset) {
        try {
            return declaredEntries(Lzf.decompressPrefix(compressed, offset + 2), offset);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Reads one string into {@code record} without keeping a materialized copy of the payload. */
    private void transferString(OutputStream record) throws IOException {
        Length length = readLengthValue(record);
        if (!length.encoded) {
            transferBytes(length.value, record);
            return;
        }
        switch ((int) length.value) {
            case 0 -> transferBytes(1, record);
            case 1 -> transferBytes(2, record);
            case 2 -> transferBytes(4, record);
            case 3 -> {
                long compressed = readLength(record);
                readLength(record); // uncompressed size — irrelevant without decompression
                transferBytes(compressed, record);
            }
            default -> throw new RespProtocolException("unknown encoded RDB string: " + length.value);
        }
    }

    private void transferBytes(long count, OutputStream record) throws IOException {
        if (count < 0)
            throw new RespProtocolException("negative RDB length");
        while (count > 0) {
            int n = (int) Math.min(transferBuffer.length, count);
            int read = input.readNBytes(transferBuffer, 0, n);
            if (read != n)
                throw truncated(n, read);
            position += read;
            crc = RedisCrc64.update(crc, transferBuffer, 0, read);
            if (record != null)
                record.write(transferBuffer, 0, read);
            count -= read;
        }
    }

    private void transferScore(int type, OutputStream record) throws IOException {
        if (type == TYPE_ZSET_2) {
            transferBytes(8, record);
            return;
        }
        int len = readUnsigned(record); // 253/254/255 specials carry no further bytes
        if (len <= 252)
            transferBytes(len, record);
    }

    private RdbEvent.KeyElement readElementStreaming(int type, int arity) throws IOException {
        byte[] member = readString(null);
        byte[] value = arity == 2 ? readString(null) : null;
        byte[] score = null;
        if (type == TYPE_ZSET)
            score = readLegacyDoubleText(null);
        else if (type == TYPE_ZSET_2)
            score = scoreText(readLittleEndianLong(null));
        return new RdbEvent.KeyElement(member, value, score);
    }

    private byte[] readLegacyDoubleText(OutputStream record) throws IOException {
        int len = readUnsigned(record);
        return switch (len) {
            case 253 -> SCORE_NEG_INF.clone();
            case 254 -> SCORE_POS_INF.clone();
            case 255 -> SCORE_NAN.clone();
            default -> readExact(len, record);
        };
    }

    private static byte[] scoreText(long bits) {
        double d = Double.longBitsToDouble(bits);
        if (Double.isNaN(d))
            return SCORE_NAN.clone();
        if (d == Double.POSITIVE_INFINITY)
            return SCORE_POS_INF.clone();
        if (d == Double.NEGATIVE_INFINITY)
            return SCORE_NEG_INF.clone();
        return Double.toString(d).getBytes(StandardCharsets.US_ASCII);
    }

    // ---------------------------------------------------------------------------------------------
    // Structure traversal shared with the legacy path
    // ---------------------------------------------------------------------------------------------

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
                skipStream(type, record);
            case TYPE_HASH_METADATA -> skipHashMetadata(record);
            case TYPE_HASH_LISTPACK_EX -> {
                readExact(8, record);
                readString(record);
            }
            case TYPE_HASH_METADATA_PRE_GA, TYPE_HASH_LISTPACK_EX_PRE_GA ->
                throw new UnsupportedRdbTypeException(type, "pre-GA hash field expiration encoding is not supported");
            case TYPE_MODULE, TYPE_MODULE_2 ->
                throw new UnsupportedRdbTypeException(type, "Redis Module data is not supported");
            default -> throw new UnsupportedRdbTypeException(type, "unknown RDB value type: " + type);
        }
    }

    private void skipHashMetadata(OutputStream record) throws IOException {
        readExact(8, record);
        long count = readLength(record);
        repeat(count, () -> {
            readLength(record);
            readString(record);
            readString(record);
        });
    }

    private void skipStream(int type, OutputStream record) throws IOException {
        long listpacks = readLength(record);
        repeat(listpacks, () -> {
            readString(record);
            readString(record);
        });
        readLength(record); // stream length
        readLength(record); // last ID ms
        readLength(record); // last ID sequence
        if (type >= TYPE_STREAM_LISTPACKS_2) {
            readLength(record); // first ID ms
            readLength(record); // first ID sequence
            readLength(record); // maximal deleted ID ms
            readLength(record); // maximal deleted ID sequence
            readLength(record); // entries added
        }
        long groups = readLength(record);
        repeat(groups, () -> {
            readString(record);
            readLength(record);
            readLength(record);
            if (type >= TYPE_STREAM_LISTPACKS_2)
                readLength(record); // entries read
            long globalPel = readLength(record);
            repeat(globalPel, () -> {
                readExact(16, record);
                readExact(8, record);
                readLength(record);
            });
            long consumers = readLength(record);
            repeat(consumers, () -> {
                readString(record);
                readExact(8, record);
                if (type >= TYPE_STREAM_LISTPACKS_3)
                    readExact(8, record);
                long localPel = readLength(record);
                repeat(localPel, () -> readExact(16, record));
            });
        });
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
            throw truncated(1, 0);
        position++;
        crc = RedisCrc64.update(crc, value);
        if (record != null)
            record.write(value);
        return value;
    }
    private byte[] readExact(int length, OutputStream record) throws IOException {
        if (length < 0)
            throw new RespProtocolException("negative RDB length");
        byte[] value = input.readNBytes(length);
        if (value.length != length)
            throw truncated(length, value.length);
        position += value.length;
        crc = RedisCrc64.update(crc, value, 0, value.length);
        if (record != null)
            record.write(value);
        return value;
    }

    private byte[] readExactRaw(int length) throws IOException {
        byte[] value = input.readNBytes(length);
        if (value.length != length)
            throw truncated(length, value.length);
        position += value.length;
        return value;
    }

    private EOFException truncated(int expected, int actual) {
        return new EOFException("truncated RDB at byte " + position + ", expected " + expected
                + " bytes but received " + actual);
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

    private static long littleEndianLong(byte[] value) {
        long result = 0;
        for (int i = value.length - 1; i >= 0; i--)
            result = (result << 8) | (value[i] & 255L);
        return result;
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

    /**
     * ByteArrayOutputStream that stops retaining bytes once the hard cap is exceeded, letting the
     * structural traversal continue (CRC and position still advance) so the remainder of an
     * oversize value is consumed correctly before it is reported as skipped.
     */
    private static final class CappedRecord extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final long cap;
        private boolean overflow;

        CappedRecord(long cap) {
            this.cap = cap;
        }

        boolean overflow() {
            return overflow;
        }

        byte[] bytes() {
            return buf.toByteArray();
        }

        @Override
        public void write(int b) {
            if (overflow)
                return;
            buf.write(b);
            if (buf.size() > cap)
                overflow = true;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (overflow)
                return;
            buf.write(b, off, len);
            if (buf.size() > cap)
                overflow = true;
        }
    }

    private record QuicklistNode(int container, byte[] blob) {
    }

    /**
     * Re-parses the bytes recorded before a threshold crossing to recover the elements/nodes they
     * contain. Working from the recorded copy (instead of buffering parsed elements from the start)
     * keeps the not-crossed common path allocation-free; failures here mean either a corrupt stream
     * or a decoder bug and fall back to RESTORE.
     */
    private static final class PrefixReader {
        private final byte[] buf;
        private int pos;

        private PrefixReader(byte[] buf) {
            this.buf = buf;
        }

        static List<RdbEvent.KeyElement> readElements(byte[] prefix, int type, long expectedElements) {
            PrefixReader r = new PrefixReader(prefix);
            if (r.readUnsignedByte() != type)
                throw corrupt("prefix type mismatch");
            long count = r.readLength();
            if (count < expectedElements)
                throw corrupt("prefix element count smaller than recorded");
            int arity = arityOf(type);
            List<RdbEvent.KeyElement> out = new ArrayList<>(Math.toIntExact(expectedElements));
            for (long i = 0; i < expectedElements; i++) {
                byte[] member = r.readString();
                byte[] value = arity == 2 ? r.readString() : null;
                byte[] score = null;
                if (type == TYPE_ZSET)
                    score = r.readLegacyDoubleText();
                else if (type == TYPE_ZSET_2)
                    score = scoreText(r.readLittleEndianLong());
                out.add(new RdbEvent.KeyElement(member, value, score));
            }
            return out;
        }

        static List<QuicklistNode> readQuicklistNodes(byte[] prefix, int type, long expectedNodes) {
            PrefixReader r = new PrefixReader(prefix);
            if (r.readUnsignedByte() != type)
                throw corrupt("prefix type mismatch");
            long count = r.readLength();
            if (count < expectedNodes)
                throw corrupt("prefix node count smaller than recorded");
            List<QuicklistNode> out = new ArrayList<>(Math.toIntExact(expectedNodes));
            for (long i = 0; i < expectedNodes; i++) {
                int container = type == TYPE_LIST_QUICKLIST_2 ? (int) r.readLength() : 1;
                if (container != 1 && container != 2)
                    throw corrupt("invalid quicklist node container: " + container);
                out.add(new QuicklistNode(container, r.readString()));
            }
            return out;
        }

        private int readUnsignedByte() {
            if (pos >= buf.length)
                throw corrupt("prefix underrun");
            return buf[pos++] & 255;
        }

        private long readLength() {
            Length length = readLengthValue();
            if (length.encoded)
                throw corrupt("encoded length where plain length expected");
            return length.value;
        }

        private Length readLengthValue() {
            int first = readUnsignedByte(), kind = (first & 0xc0) >>> 6;
            return switch (kind) {
                case 0 -> new Length(first & 0x3f, false);
                case 1 -> new Length(((first & 0x3f) << 8) | readUnsignedByte(), false);
                case 2 -> {
                    int selector = first & 0x3f;
                    if (selector == 0)
                        yield new Length(readBigEndianInt() & 0xffffffffL, false);
                    if (selector == 1)
                        yield new Length(readBigEndianLong(), false);
                    throw corrupt("invalid RDB length selector: " + selector);
                }
                case 3 -> new Length(first & 0x3f, true);
                default -> throw new IllegalStateException();
            };
        }

        private byte[] readString() {
            Length length = readLengthValue();
            if (!length.encoded)
                return slice(Math.toIntExact(length.value));
            return switch ((int) length.value) {
                case 0 -> integerBytes((byte) readUnsignedByte());
                case 1 -> integerBytes((short) readLittleEndian(2));
                case 2 -> integerBytes((int) readLittleEndian(4));
                case 3 -> {
                    long compressed = readLength(), uncompressed = readLength();
                    yield Lzf.decompress(slice(Math.toIntExact(compressed)), Math.toIntExact(uncompressed));
                }
                default -> throw corrupt("unknown encoded RDB string: " + length.value);
            };
        }

        private byte[] readLegacyDoubleText() {
            int len = readUnsignedByte();
            return switch (len) {
                case 253 -> SCORE_NEG_INF.clone();
                case 254 -> SCORE_POS_INF.clone();
                case 255 -> SCORE_NAN.clone();
                default -> slice(len);
            };
        }

        private byte[] slice(int len) {
            if (len < 0 || pos + len > buf.length)
                throw corrupt("string reaches past prefix end");
            byte[] value = new byte[len];
            System.arraycopy(buf, pos, value, 0, len);
            pos += len;
            return value;
        }

        private long readLittleEndian(int bytes) {
            if (pos + bytes > buf.length)
                throw corrupt("read reaches past prefix end");
            long value = 0;
            for (int i = bytes - 1; i >= 0; i--)
                value = (value << 8) | (buf[pos + i] & 255L);
            pos += bytes;
            return value;
        }

        private long readLittleEndianLong() {
            return readLittleEndian(8);
        }

        private int readBigEndianInt() {
            if (pos + 4 > buf.length)
                throw corrupt("read reaches past prefix end");
            int value = ((buf[pos] & 255) << 24) | ((buf[pos + 1] & 255) << 16) | ((buf[pos + 2] & 255) << 8)
                    | (buf[pos + 3] & 255);
            pos += 4;
            return value;
        }

        private long readBigEndianLong() {
            if (pos + 8 > buf.length)
                throw corrupt("read reaches past prefix end");
            long value = 0;
            for (int i = 0; i < 8; i++)
                value = (value << 8) | (buf[pos + i] & 255L);
            pos += 8;
            return value;
        }

        private static RespProtocolException corrupt(String detail) {
            return new RespProtocolException("corrupt recorded prefix: " + detail);
        }
    }

    static final class Lzf {
        static byte[] decompress(byte[] input, int outputLength) {
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

        /**
         * Inflates at most {@code limit} bytes and stops there — a prefix read that never
         * materializes the full output. Throws when the input cannot produce {@code limit} bytes;
         * the caller treats that as an unreadable count, not a parse failure.
         */
        static byte[] decompressPrefix(byte[] input, int limit) {
            byte[] output = new byte[limit];
            int in = 0, out = 0;
            while (out < limit) {
                if (in >= input.length)
                    throw new RespProtocolException("LZF prefix exhausted");
                int control = input[in++] & 255;
                if (control < 32) {
                    int length = control + 1;
                    if (in + length > input.length)
                        throw new RespProtocolException("invalid LZF literal");
                    int take = Math.min(length, limit - out);
                    System.arraycopy(input, in, output, out, take);
                    in += length;
                    out += take;
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
                    if (reference < 0)
                        throw new RespProtocolException("invalid LZF back reference");
                    int take = Math.min(length, limit - out);
                    for (int i = 0; i < take; i++)
                        output[out++] = output[reference + i];
                }
            }
            return output;
        }
    }
}
