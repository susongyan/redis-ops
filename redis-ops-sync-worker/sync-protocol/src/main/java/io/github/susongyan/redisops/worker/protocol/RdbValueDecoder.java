package io.github.susongyan.redisops.worker.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the compact container blobs found inside RDB values (ziplist / listpack / intset /
 * zipmap) into their flat string entries, byte-exact per the Redis reference implementations.
 * Malformed blobs throw {@link RespProtocolException}; the parser catches that and falls back to
 * RESTORE so a decoder disagreement never loses data.
 *
 * <p>
 * Wire facts used below (verified against redis/redis 7.4 sources):
 * <ul>
 * <li>ziplist: u32le total bytes, u32le tail offset, u16le entry count, entries, 0xFF end;
 * entry = prevlen (1B or 0xFE+u32le), encoding (00/01/10xx string lengths — the 14/32-bit
 * lengths are BIG-endian — or 0xC0/0xD0/0xE0/0xF0/0xFE/0xF1..0xFD little-endian integers),
 * 0xFF terminator.</li>
 * <li>listpack: u32le total bytes, u16le element count, entries, 0xFF end; entry encodings as in
 * {@code listpack.c} (7-bit uint, 6/12/32-bit string, 13/16/24/32/64-bit little-endian int)
 * followed by a backlen whose byte count is derived from the entry length thresholds.</li>
 * <li>intset: u32le encoding (2/4/8 = entry width), u32le count, little-endian signed entries.</li>
 * <li>zipmap: u8 entry count (254 = walk), then per entry a length-prefixed key (1B length, or
 * 254 + u32le) followed by the value as [len][free byte][value], 0xFF end.</li>
 * </ul>
 */
final class RdbValueDecoder {
    private RdbValueDecoder() {
    }

    /** Flat entries of a whole-value blob (hash/zset blobs alternate field/value, member/score). */
    static List<byte[]> decode(int rdbType, byte[] blob) {
        return switch (rdbType) {
            case RdbStreamParser.TYPE_HASH_ZIPMAP -> zipmapEntries(blob);
            case RdbStreamParser.TYPE_LIST_ZIPLIST, RdbStreamParser.TYPE_HASH_ZIPLIST,
                    RdbStreamParser.TYPE_ZSET_ZIPLIST ->
                ziplistEntries(blob);
            case RdbStreamParser.TYPE_SET_INTSET -> intsetMembers(blob);
            case RdbStreamParser.TYPE_HASH_LISTPACK, RdbStreamParser.TYPE_ZSET_LISTPACK,
                    RdbStreamParser.TYPE_SET_LISTPACK ->
                listpackEntries(blob);
            default -> throw new RespProtocolException("type " + rdbType + " has no compact blob to decode");
        };
    }

    static List<byte[]> ziplistEntries(byte[] zl) {
        if (zl.length < 11)
            throw corrupt("ziplist truncated header");
        if (u32le(zl, 0) != zl.length)
            throw corrupt("ziplist total bytes mismatch");
        List<byte[]> out = new ArrayList<>();
        int p = 10;
        while (true) {
            int prev = u8(zl, p);
            if (prev == 0xFF) {
                if (p != zl.length - 1)
                    throw corrupt("ziplist end marker not at tail");
                return out;
            }
            p += prev == 0xFE ? 5 : 1;
            int enc = u8(zl, p++);
            if ((enc & 0xC0) == 0x00) {
                p = appendSlice(out, zl, p, enc & 0x3F);
            } else if ((enc & 0xC0) == 0x40) {
                int len = ((enc & 0x3F) << 8) | u8(zl, p);
                p = appendSlice(out, zl, p + 1, len);
            } else if (enc == 0x80) {
                int len = (u8(zl, p) << 24) | (u8(zl, p + 1) << 16) | (u8(zl, p + 2) << 8) | u8(zl, p + 3);
                p = appendSlice(out, zl, p + 4, len);
            } else if (enc == 0xC0) {
                out.add(ascii(((short) (int) le(zl, p, 2))));
                p += 2;
            } else if (enc == 0xD0) {
                out.add(ascii((int) le(zl, p, 4)));
                p += 4;
            } else if (enc == 0xE0) {
                out.add(ascii(le(zl, p, 8)));
                p += 8;
            } else if (enc == 0xF0) {
                int v = (int) le(zl, p, 3);
                out.add(ascii((long) (v << 8 >> 8))); // sign-extend 24-bit
                p += 3;
            } else if (enc == 0xFE) {
                out.add(ascii((long) (byte) u8(zl, p)));
                p += 1;
            } else if (enc >= 0xF1 && enc <= 0xFD) {
                out.add(ascii((long) (enc & 0x0F) - 1));
            } else {
                throw corrupt("unknown ziplist entry encoding 0x" + Integer.toHexString(enc));
            }
        }
    }

    static List<byte[]> listpackEntries(byte[] lp) {
        if (lp.length < 7)
            throw corrupt("listpack truncated header");
        if (u32le(lp, 0) != lp.length)
            throw corrupt("listpack total bytes mismatch");
        List<byte[]> out = new ArrayList<>();
        int p = 6;
        while (true) {
            int enc = u8(lp, p);
            if (enc == 0xFF) {
                if (p != lp.length - 1)
                    throw corrupt("listpack end marker not at tail");
                return out;
            }
            int entryLen; // encoding byte(s) + payload, excluding backlen
            if (enc < 0x80) { // 7-bit unsigned integer
                out.add(ascii(enc));
                entryLen = 1;
            } else if ((enc & 0xC0) == 0x80) { // 6-bit string
                int len = enc & 0x3F;
                appendSlice(out, lp, p + 1, len);
                entryLen = 1 + len;
            } else if ((enc & 0xE0) == 0xC0) { // 13-bit signed integer
                int v = ((enc & 0x1F) << 8) | u8(lp, p + 1);
                out.add(ascii(v >= (1 << 12) ? v - (1 << 13) : v));
                entryLen = 2;
            } else if ((enc & 0xF0) == 0xE0) { // 12-bit string
                int len = ((enc & 0x0F) << 8) | u8(lp, p + 1);
                appendSlice(out, lp, p + 2, len);
                entryLen = 2 + len;
            } else if (enc == 0xF0) { // 32-bit string length, little-endian
                int len = (int) le(lp, p + 1, 4);
                appendSlice(out, lp, p + 5, len);
                entryLen = 5 + len;
            } else if (enc == 0xF1) { // 16-bit signed integer
                out.add(ascii(((short) (int) le(lp, p + 1, 2))));
                entryLen = 3;
            } else if (enc == 0xF2) { // 24-bit signed integer
                int v = (int) le(lp, p + 1, 3);
                out.add(ascii((long) (v << 8 >> 8)));
                entryLen = 4;
            } else if (enc == 0xF3) { // 32-bit signed integer
                out.add(ascii((int) le(lp, p + 1, 4)));
                entryLen = 5;
            } else if (enc == 0xF4) { // 64-bit signed integer
                out.add(ascii(le(lp, p + 1, 8)));
                entryLen = 9;
            } else {
                throw corrupt("unknown listpack entry encoding 0x" + Integer.toHexString(enc));
            }
            p += entryLen + backlenBytes(entryLen);
        }
    }

    static List<byte[]> intsetMembers(byte[] set) {
        if (set.length < 8)
            throw corrupt("intset truncated header");
        long encoding = le(set, 0, 4);
        if (encoding != 2 && encoding != 4 && encoding != 8)
            throw corrupt("unknown intset encoding " + encoding);
        long count = le(set, 4, 4);
        if (set.length != 8 + count * encoding)
            throw corrupt("intset size mismatch");
        List<byte[]> out = new ArrayList<>(Math.toIntExact(count));
        for (long i = 0; i < count; i++) {
            int p = (int) (8 + i * encoding);
            out.add(ascii(encoding == 2
                    ? ((short) (int) le(set, p, 2))
                    : encoding == 4 ? (int) le(set, p, 4) : le(set, p, 8)));
        }
        return out;
    }

    static List<byte[]> zipmapEntries(byte[] zm) {
        List<byte[]> out = new ArrayList<>();
        int p = 1; // skip the u8 entry count; 254 means unknown and we walk anyway
        while (true) {
            if (u8(zm, p) == 0xFF)
                return out;
            p = zipmapAppend(out, zm, p); // key: [len][key]
            int len = u8(zm, p++); // value: [len][free][value]
            if (len == 254) {
                len = (int) le(zm, p, 4);
                p += 4;
            }
            p = appendSlice(out, zm, p + 1, len); // +1 skips the free byte
        }
    }

    /** Byte size of the listpack backlen for an entry of {@code entryLen} bytes (excl. backlen). */
    static int backlenBytes(int entryLen) {
        if (entryLen <= 127)
            return 1;
        if (entryLen < 16383)
            return 2;
        if (entryLen < 2097151)
            return 3;
        if (entryLen < 268435455)
            return 4;
        return 5;
    }

    private static int zipmapAppend(List<byte[]> out, byte[] zm, int p) {
        int len = u8(zm, p++);
        if (len == 254) {
            len = (int) le(zm, p, 4);
            p += 4;
        }
        return appendSlice(out, zm, p, len);
    }

    /** Reads {@code len} bytes at {@code p}, appends a copy and returns the new position. */
    private static int appendSlice(List<byte[]> out, byte[] buf, int p, int len) {
        if (len < 0 || p < 0 || len > buf.length - p)
            throw corrupt("entry reaches past blob end");
        byte[] value = new byte[len];
        System.arraycopy(buf, p, value, 0, len);
        out.add(value);
        return p + len;
    }

    private static long le(byte[] buf, int p, int bytes) {
        checkRange(buf, p, bytes);
        long value = 0;
        for (int i = bytes - 1; i >= 0; i--)
            value = (value << 8) | (buf[p + i] & 255L);
        return value;
    }

    private static long u32le(byte[] buf, int p) {
        return le(buf, p, 4);
    }

    private static int u8(byte[] buf, int p) {
        checkRange(buf, p, 1);
        return buf[p] & 255;
    }

    private static void checkRange(byte[] buf, int p, int bytes) {
        if (p < 0 || bytes < 0 || bytes > buf.length - p)
            throw corrupt("read reaches past blob end");
    }

    private static byte[] ascii(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }

    private static RespProtocolException corrupt(String detail) {
        return new RespProtocolException("corrupt compact encoding: " + detail);
    }
}
