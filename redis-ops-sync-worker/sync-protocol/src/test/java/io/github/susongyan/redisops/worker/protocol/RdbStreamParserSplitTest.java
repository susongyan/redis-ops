package io.github.susongyan.redisops.worker.protocol;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Route coverage for {@code RdbStreamParser.parse(consumer, SplitOptions)}. */
class RdbStreamParserSplitTest {
    private static final int TYPE_STRING = 0, TYPE_LIST = 1, TYPE_SET = 2, TYPE_ZSET = 3, TYPE_HASH = 4,
            TYPE_ZSET_2 = 5, TYPE_MODULE_2 = 7, TYPE_LIST_ZIPLIST = 10, TYPE_SET_INTSET = 11,
            TYPE_HASH_ZIPLIST = 13, TYPE_LIST_QUICKLIST = 14, TYPE_HASH_LISTPACK = 16,
            TYPE_LIST_QUICKLIST_2 = 18, TYPE_SET_LISTPACK = 20;

    @Test
    void smallKeysStayOnRestorePathIdenticalToLegacy() throws Exception {
        Rdb rdb = new Rdb();
        rdb.key("k1", TYPE_STRING, s -> rdb.string("v1"));
        rdb.key("k2", TYPE_LIST, s -> {
            rdb.length(2);
            rdb.string("a");
            rdb.string("b");
        });
        rdb.key("k3", TYPE_HASH_ZIPLIST, s -> rdb.string(ziplist("f1", "v1")));
        List<RdbEvent> legacy = new ArrayList<>();
        new RdbStreamParser(new ByteArrayInputStream(rdb.bytes())).parse(legacy::add);
        List<RdbEvent> split = new ArrayList<>();
        new RdbStreamParser(new ByteArrayInputStream(rdb.bytes()))
                .parse(split::add, new SplitOptions(1024, 4096, false));
        assertEquals(legacy.size(), split.size());
        assertInstanceOf(RdbEvent.SelectDb.class, split.get(0));
        for (int i = 1; i < legacy.size() - 1; i++) { // index 0 is SelectDb, last is End
            assertInstanceOf(RdbEvent.KeyValue.class, split.get(i), "event " + i);
            var a = (RdbEvent.KeyValue) legacy.get(i);
            var b = (RdbEvent.KeyValue) split.get(i);
            assertArrayEquals(a.key(), b.key(), "event " + i);
            assertArrayEquals(a.dumpPayload(), b.dumpPayload(), "event " + i);
        }
    }

    @Test
    void oversizeListStreamsElementsWithPrefixReparse() throws Exception {
        // 4 members of 100 bytes; threshold 150 crosses after the second element
        Rdb rdb = bigList("big-list", 4, 100);
        List<RdbEvent> events = parse(rdb, new SplitOptions(150, 4096, false));
        var begin = (RdbEvent.BigKeyValueBegin) events.get(1);
        assertEquals(TYPE_LIST, begin.rdbType());
        assertEquals(4, begin.estimatedElements());
        List<String> members = elements(events);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            expected.add("a" + i + " ".repeat(98));
        assertEquals(expected, members);
        var end = (RdbEvent.BigKeyValueEnd) last(events, RdbEvent.BigKeyValueEnd.class);
        assertEquals(4, end.elementCount());
        // count byte + 4 x (2-byte 14-bit length + 100-byte payload); 100 >= 64 needs the wide form
        assertEquals(1 + 4 * 102, end.payloadBytes());
    }

    @Test
    void hashAndSetSplitByPairsAndSingles() throws Exception {
        Rdb rdb = new Rdb();
        rdb.key("big-hash", TYPE_HASH, s -> {
            rdb.length(3);
            for (int i = 0; i < 3; i++) {
                rdb.string("field-" + i);
                rdb.string("v".repeat(100));
            }
        });
        rdb.key("big-set", TYPE_SET, s -> {
            rdb.length(3);
            for (int i = 0; i < 3; i++)
                rdb.string("s".repeat(100) + i);
        });
        List<RdbEvent> events = parse(rdb, new SplitOptions(150, 8192, false));
        var hashBegin = (RdbEvent.BigKeyValueBegin) events.get(1);
        assertEquals(TYPE_HASH, hashBegin.rdbType());
        var f0 = element(events, 2);
        assertEquals("field-0", new String(f0.member()));
        assertEquals("v", new String(f0.value()).substring(0, 1));
        assertNull(f0.score());
        var setBegin = (RdbEvent.BigKeyValueBegin) events.get(6);
        assertEquals(TYPE_SET, setBegin.rdbType());
        var member = element(events, 7);
        assertEquals("s", new String(member.member()).substring(0, 1));
        assertNull(member.value());
    }

    @Test
    void zsetStreamsLegacyAndBinaryScores() throws Exception {
        Rdb rdb = new Rdb();
        rdb.key("z-legacy", TYPE_ZSET, s -> {
            rdb.length(3);
            rdb.string(member("m1"));
            rdb.string("1.5");
            rdb.string(member("m2"));
            rdb.write(253); // -inf
            rdb.string(member("m3"));
            rdb.write(254); // +inf
        });
        rdb.key("z-binary", TYPE_ZSET_2, s -> {
            rdb.length(3);
            rdb.string(member("m1"));
            rdb.writeDoubleLe(Double.doubleToLongBits(2.5));
            rdb.string(member("m2"));
            rdb.writeDoubleLe(Double.doubleToLongBits(Double.POSITIVE_INFINITY));
            rdb.string(member("m3"));
            rdb.writeDoubleLe(Double.doubleToLongBits(-0.5));
        });
        List<RdbEvent> events = parse(rdb, new SplitOptions(40, 8192, false));
        assertEquals("1.5", new String(element(events, 2).score()));
        assertEquals("-inf", new String(element(events, 3).score()));
        assertEquals("+inf", new String(element(events, 4).score()));
        assertEquals("2.5", new String(element(events, 7).score()));
        assertEquals("+inf", new String(element(events, 8).score()));
        assertEquals("-0.5", new String(element(events, 9).score()));
    }

    /** 20-byte member so a (member, score) pair crosses the small test thresholds. */
    private static String member(String label) {
        return label + " ".repeat(20 - label.length());
    }

    @Test
    void prefixReparseHandlesEncodedElements() throws Exception {
        // int-encoded member and an LZF-compressed member inside the recorded prefix
        Rdb rdb = new Rdb();
        rdb.key("enc", TYPE_SET, s -> {
            rdb.length(3);
            rdb.writeIntEncodedMember((byte) 100); // 0xC0|0 + one byte, in int8 range
            rdb.writeLzfMember("l".repeat(20));
            rdb.string("p".repeat(50));
        });
        List<RdbEvent> events = parse(rdb, new SplitOptions(60, 4096, false));
        List<String> members = elements(events);
        assertEquals(List.of("100", "l".repeat(20), "p".repeat(50)), members);
    }

    @Test
    void quicklistSplitsAcrossZiplistAndListpackNodes() throws Exception {
        Rdb rdb = new Rdb();
        rdb.key("ql1", TYPE_LIST_QUICKLIST, s -> {
            rdb.length(2);
            rdb.string(ziplist("n1a", "n1b"));
            rdb.string(ziplist("n2a"));
        });
        rdb.key("ql2", TYPE_LIST_QUICKLIST_2, s -> {
            rdb.length(2);
            rdb.length(1); // packed listpack node
            rdb.string(listpack("p1", "p2"));
            rdb.length(2); // plain node holding one element
            rdb.string("plain-element");
        });
        List<RdbEvent> events = parse(rdb, new SplitOptions(20, 4096, false));
        List<String> first = elementsUntil(events, 1, "ql2");
        assertEquals(List.of("n1a", "n1b", "n2a"), first);
        int secondBegin = -1;
        for (int i = 1; i < events.size(); i++)
            if (events.get(i) instanceof RdbEvent.BigKeyValueBegin b && new String(b.key()).equals("ql2"))
                secondBegin = i;
        List<String> second = elementsUntil(events, secondBegin, null);
        assertEquals(List.of("p1", "p2", "plain-element"), second);
    }

    @Test
    void compactBlobOverThresholdDecodesAndFallsBackOnCorruption() throws Exception {
        Rdb rdb = new Rdb();
        rdb.key("lp-set", TYPE_SET_LISTPACK, s -> rdb.string(listpack("m1", "m2")));
        List<RdbEvent> events = parse(rdb, new SplitOptions(4, 4096, false));
        assertEquals(List.of("m1", "m2"), elements(events));

        Rdb corrupt = new Rdb();
        corrupt.key("bad", TYPE_SET_LISTPACK, s -> corrupt.string(new byte[]{7, 0, 0, 0, 9, 9, (byte) 0x84}));
        List<RdbEvent> fallback = parse(corrupt, new SplitOptions(4, 4096, false));
        var kv = (RdbEvent.KeyValue) fallback.get(1);
        assertEquals(1, count(fallback, RdbEvent.KeyValue.class));
        assertArrayEquals("bad".getBytes(StandardCharsets.US_ASCII), kv.key());
    }

    @Test
    void oversizeValuesAreSkippedAndParsingContinues() throws Exception {
        Rdb rdb = new Rdb();
        rdb.key("huge-string", TYPE_STRING, s -> rdb.string("x".repeat(300)));
        rdb.key("huge-list", TYPE_LIST, s -> {
            rdb.length(3);
            for (int i = 0; i < 3; i++)
                rdb.string("y".repeat(100));
        });
        rdb.key("next-key", TYPE_STRING, s -> rdb.string("ok"));
        List<RdbEvent> events = parse(rdb, new SplitOptions(150, 200, false));
        var skippedString = (RdbEvent.SkippedKey) events.get(1);
        assertEquals(RdbEvent.SkipReason.OVER_HARD_CAP, skippedString.reason());
        assertEquals(302, skippedString.payloadBytes()); // 2-byte length header + 300 bytes
        // list of ~304 bytes trips the 200-byte hard cap during recording (before the
        // 150-byte threshold split would engage) and is skipped without a Begin event
        var skippedList = (RdbEvent.SkippedKey) events.get(2);
        assertEquals(RdbEvent.SkipReason.OVER_HARD_CAP, skippedList.reason());
        var next = (RdbEvent.KeyValue) events.get(3);
        assertArrayEquals("next-key".getBytes(StandardCharsets.US_ASCII), next.key());
    }

    @Test
    void hardCapMidStreamAbortsOpenSplit() throws Exception {
        // threshold 150 crosses after element 2; hard cap 300 trips while streaming element 3
        Rdb rdb = bigList("mid-abort", 4, 100);
        List<RdbEvent> events = parse(rdb, new SplitOptions(150, 300, false));
        var begin = (RdbEvent.BigKeyValueBegin) events.get(1);
        assertEquals("mid-abort", new String(begin.key()));
        assertEquals(2, count(events, RdbEvent.KeyElement.class)); // elements 1-2 emitted before the abort
        var skipped = (RdbEvent.SkippedKey) last(events, RdbEvent.SkippedKey.class);
        assertEquals(RdbEvent.SkipReason.OVER_HARD_CAP, skipped.reason());
        // the whole value was still consumed: 1 count byte + 4 x (2-byte length + 100 bytes)
        assertEquals(1 + 4 * 102, skipped.payloadBytes());
        assertEquals(RdbEvent.End.INSTANCE, events.get(events.size() - 1));
    }

    @Test
    void module2IsSkippedOnlyWhenOptedIn() throws Exception {
        Rdb module2 = new Rdb();
        module2.key("mod", TYPE_MODULE_2, s -> {
            s.length(0x1234); // module id
            s.length(5); // STRING opcode
            s.string("payload");
            s.length(4); // DOUBLE opcode
            s.writeLongLe(0);
            s.length(0); // EOF opcode
        });
        List<RdbEvent> skipped = parse(module2, new SplitOptions(100, 4096, true));
        var event = (RdbEvent.SkippedKey) skipped.get(1);
        assertEquals(RdbEvent.SkipReason.UNSUPPORTED_TYPE, event.reason());
        assertArrayEquals("mod".getBytes(StandardCharsets.US_ASCII), event.key());
        assertEquals(RdbEvent.End.INSTANCE, skipped.get(2));
        assertThrows(UnsupportedRdbTypeException.class,
                () -> parse(module2, new SplitOptions(100, 4096, false)));
    }

    @Test
    void expireAtFlowsIntoBeginEventAndResetsAfter() throws Exception {
        Rdb rdb = new Rdb();
        rdb.expire(1_700_000_000_000L);
        rdb.key("t", TYPE_SET, s -> {
            rdb.length(2);
            rdb.string("a".repeat(100));
            rdb.string("b".repeat(100));
        });
        rdb.key("plain", TYPE_STRING, s -> rdb.string("x"));
        List<RdbEvent> events = parse(rdb, new SplitOptions(100, 4096, false));
        var begin = (RdbEvent.BigKeyValueBegin) events.get(1);
        assertEquals(1_700_000_000_000L, begin.absoluteExpireMillis());
        var plain = (RdbEvent.KeyValue) events.get(events.size() - 2);
        assertEquals(-1, plain.absoluteExpireMillis());
    }

    // ---------------------------------------------------------------------------------------------

    private static List<RdbEvent> parse(Rdb rdb, SplitOptions options) throws Exception {
        List<RdbEvent> events = new ArrayList<>();
        new RdbStreamParser(new ByteArrayInputStream(rdb.bytes())).parse(events::add, options);
        return events;
    }

    private static Rdb bigList(String key, int count, int memberBytes) {
        Rdb rdb = new Rdb();
        rdb.key(key, TYPE_LIST, s -> {
            rdb.length(count);
            for (int i = 0; i < count; i++)
                rdb.string("a" + i + " ".repeat(memberBytes - ("a" + i).length()));
        });
        return rdb;
    }

    private static List<String> elements(List<RdbEvent> events) {
        List<String> out = new ArrayList<>();
        for (RdbEvent event : events)
            if (event instanceof RdbEvent.KeyElement e)
                out.add(new String(e.member()));
        return out;
    }

    private static RdbEvent.KeyElement element(List<RdbEvent> events, int index) {
        return (RdbEvent.KeyElement) events.get(index);
    }

    private static List<String> elementsUntil(List<RdbEvent> events, int from, String stopKey) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < events.size(); i++) {
            RdbEvent event = events.get(i);
            if (event instanceof RdbEvent.KeyElement e)
                out.add(new String(e.member()));
            else if (event instanceof RdbEvent.BigKeyValueBegin b
                    && stopKey != null && new String(b.key()).equals(stopKey))
                return out;
            else if (event instanceof RdbEvent.BigKeyValueEnd)
                return out;
        }
        return out;
    }

    private static <T> T last(List<RdbEvent> events, Class<T> type) {
        T found = null;
        for (RdbEvent event : events)
            if (type.isInstance(event))
                found = type.cast(event);
        assertNotNull(found);
        return found;
    }

    private static long count(List<RdbEvent> events, Class<?> type) {
        return events.stream().filter(type::isInstance).count();
    }

    // ---------------------------------------------------------------------------------------------

    /** Hand-rolled RDB byte stream builder with the length/string primitives the parser reads. */
    static final class Rdb {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Rdb() {
            out.writeBytes("REDIS0009".getBytes(StandardCharsets.US_ASCII));
            out.write(254);
            out.write(0); // SELECTDB 0
        }

        byte[] bytes() {
            out.write(255);
            out.writeBytes(new byte[8]); // zero checksum is accepted
            return out.toByteArray();
        }

        void expire(long millis) {
            out.write(252);
            writeLongLe(millis);
        }

        @FunctionalInterface
        interface Value {
            void write(Rdb rdb);
        }

        void key(String key, int type, Value value) {
            out.write(type);
            string(key);
            value.write(this);
        }

        void length(long value) {
            if (value < 0)
                throw new IllegalArgumentException();
            if (value < 64) {
                out.write((int) value);
            } else if (value < 16384) {
                out.write(0x40 | (int) (value >> 8));
                out.write((int) (value & 0xFF));
            } else {
                out.write(0x80);
                out.write(0);
                writeIntBe((int) value);
            }
        }

        void string(byte[] bytes) {
            length(bytes.length);
            out.writeBytes(bytes);
        }

        void string(String value) {
            string(value.getBytes(StandardCharsets.US_ASCII));
        }

        void writeIntEncodedMember(byte value) {
            out.write(0xC0); // encoded kind 0 = int8
            out.write(value);
        }

        void writeLzfMember(String value) {
            byte[] plain = value.getBytes(StandardCharsets.US_ASCII);
            // literal-only LZF: [control = len-1][bytes] (valid for len <= 32)
            if (plain.length > 32)
                throw new IllegalArgumentException("test LZF only supports short literals");
            out.write(0xC0 | 3); // encoded kind 3 = LZF
            length(plain.length + 1);
            length(plain.length);
            out.write(plain.length - 1);
            out.writeBytes(plain);
        }

        void write(int b) {
            out.write(b);
        }

        void writeDoubleLe(long bits) {
            writeLongLe(bits);
        }

        void writeLongLe(long value) {
            for (int i = 0; i < 8; i++)
                out.write((int) (value >>> (8 * i)) & 0xff);
        }

        private void writeIntBe(int value) {
            out.write((value >>> 24) & 0xff);
            out.write((value >>> 16) & 0xff);
            out.write((value >>> 8) & 0xff);
            out.write(value & 0xff);
        }
    }

    static byte[] ziplist(String... entries) {
        var body = new ByteArrayOutputStream();
        int lastEntryOffset = 10; // zltail points at the final entry
        for (int i = 0; i < entries.length; i++) {
            byte[] bytes = entries[i].getBytes(StandardCharsets.US_ASCII);
            int prevLen = body.size();
            if (prevLen >= 254)
                throw new UnsupportedOperationException("test ziplists stay small");
            body.write(prevLen);
            if (bytes.length <= 63) {
                body.write(bytes.length);
            } else {
                body.write(0x40 | (bytes.length >> 8));
                body.write(bytes.length & 0xFF);
            }
            body.writeBytes(bytes);
            if (i < entries.length - 1)
                lastEntryOffset += 1 + encLen(entries[i]) + bytes.length;
        }
        body.write(0xFF);
        byte[] zl = new byte[10 + body.size()];
        writeIntLe(zl, 0, zl.length, 4); // zlbytes
        writeIntLe(zl, 4, lastEntryOffset, 4); // zltail
        writeIntLe(zl, 8, entries.length, 2); // zllen
        byte[] entryBytes = body.toByteArray();
        System.arraycopy(entryBytes, 0, zl, 10, entryBytes.length);
        return zl;
    }

    private static int encLen(String entry) {
        return entry.length() <= 63 ? 1 : 2;
    }

    static byte[] listpack(String... entries) {
        var body = new ByteArrayOutputStream();
        for (String entry : entries) {
            byte[] bytes = entry.getBytes(StandardCharsets.US_ASCII);
            int entryLen;
            if (bytes.length < 64) {
                body.write(0x80 | bytes.length);
                entryLen = 1 + bytes.length;
            } else if (bytes.length < 4096) {
                body.write(0xE0 | (bytes.length >> 8));
                body.write(bytes.length & 0xFF);
                entryLen = 2 + bytes.length;
            } else {
                body.write(0xF0);
                writeIntLe(body, bytes.length, 4);
                entryLen = 5 + bytes.length;
            }
            body.writeBytes(bytes);
            body.write(entryLen); // backlen (test entries stay below 127 bytes)
        }
        body.write(0xFF);
        var lp = new ByteArrayOutputStream();
        writeIntLe(lp, 6 + body.size(), 4);
        writeIntLe(lp, entries.length, 2);
        lp.writeBytes(body.toByteArray());
        return lp.toByteArray();
    }

    private static void writeIntLe(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++)
            out.write((int) (value >>> (8 * i)) & 0xff);
    }

    private static void writeIntLe(byte[] buf, int pos, long value, int bytes) {
        for (int i = 0; i < bytes; i++)
            buf[pos + i] = (byte) ((value >>> (8 * i)) & 0xff);
    }
}
