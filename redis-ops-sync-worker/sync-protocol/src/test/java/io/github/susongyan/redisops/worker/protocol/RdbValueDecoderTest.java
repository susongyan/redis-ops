package io.github.susongyan.redisops.worker.protocol;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RdbValueDecoderTest {
    @Test
    void decodesGoldenListpackFromRedisTestVector() {
        // listpack.c "Replace with same size": hello, squirrel, quux, 1024 (13-bit int), 33 bytes total
        var lp = new ByteArrayOutputStream();
        writeIntLe(lp, 33, 4);
        writeIntLe(lp, 4, 2);
        lp.writeBytes(new byte[]{(byte) 0x85});
        lp.writeBytes("hello".getBytes(StandardCharsets.US_ASCII));
        lp.write(0x06);
        lp.writeBytes(new byte[]{(byte) 0x88});
        lp.writeBytes("squirrel".getBytes(StandardCharsets.US_ASCII));
        lp.write(0x09);
        lp.writeBytes(new byte[]{(byte) 0x84});
        lp.writeBytes("quux".getBytes(StandardCharsets.US_ASCII));
        lp.write(0x05);
        lp.writeBytes(new byte[]{(byte) 0xC4, 0x00, 0x02});
        lp.write(0xFF);
        byte[] blob = lp.toByteArray();
        assertEquals(33, blob.length);
        assertEntries(RdbValueDecoder.listpackEntries(blob), "hello", "squirrel", "quux", "1024");
    }

    @Test
    void decodesGoldenZiplistFromRedisSpec() {
        // ziplist.c header example: [0f 00 00 00][0c 00 00 00][02 00][00 f3][02 f6][ff] = "2","5"
        byte[] zl = {15, 0, 0, 0, 12, 0, 0, 0, 2, 0, 0x00, (byte) 0xF3, 0x02, (byte) 0xF6, (byte) 0xFF};
        assertEntries(RdbValueDecoder.ziplistEntries(zl), "2", "5");
    }

    @Test
    void decodesEveryZiplistEncoding() {
        var zl = new ByteArrayOutputStream();
        var body = new ByteArrayOutputStream();
        body.write(0); // prevlen
        body.write(0x05); // 6-bit string
        body.writeBytes("abcde".getBytes(StandardCharsets.US_ASCII));
        writeZiplistEntry(body, 100, (byte) 0x40, 2); // 14-bit string length, big-endian bits
        body.write(0); // prevlen
        body.write(0x80); // 32-bit string length, big-endian
        writeIntBe(body, 300);
        body.writeBytes(("x".repeat(300)).getBytes(StandardCharsets.US_ASCII));
        writeZiplistInt(body, 0xC0, (short) -12345);
        writeZiplistInt(body, 0xD0, 123456789);
        writeZiplistInt(body, 0xE0, -9_000_000_000_000_000_000L);
        body.write(0);
        body.write(0xF0); // int24
        body.write(0);
        body.write(0);
        body.write((byte) 0x80); // -8388608
        body.write(0);
        body.write(0xFE); // int8
        body.write((byte) 0xF7); // -9
        body.write(0);
        body.write(0xF1); // immediate 0
        body.write(0);
        body.write(0xFD); // immediate 12
        body.write(0xFF);
        writeIntLe(zl, 10 + body.size(), 4);
        writeIntLe(zl, 10, 4);
        writeIntLe(zl, 10, 2);
        zl.writeBytes(body.toByteArray());
        assertEntries(RdbValueDecoder.ziplistEntries(zl.toByteArray()),
                "abcde", "y".repeat(100), "x".repeat(300), "-12345", "123456789",
                "-9000000000000000000", "-8388608", "-9", "0", "12");
    }

    @Test
    void decodesEveryListpackEncoding() {
        var lp = new ByteArrayOutputStream();
        var body = new ByteArrayOutputStream();
        body.write(42); // 7-bit uint
        writeBacklen(body, 1);
        body.write(0x80 | 3); // 6-bit string
        body.writeBytes("abc".getBytes(StandardCharsets.US_ASCII));
        writeBacklen(body, 4);
        body.write((byte) (0xC0 | (4095 >> 8))); // 13-bit int 4095
        body.write(4095 & 0xFF);
        writeBacklen(body, 2);
        body.write((byte) (0xC0 | (-4096 + 8192) >> 8)); // 13-bit int -4096
        body.write((-4096 + 8192) & 0xFF);
        writeBacklen(body, 2);
        body.write((byte) (0xE0 | (4000 >> 8))); // 12-bit string
        body.write(4000 & 0xFF);
        body.writeBytes(("z".repeat(4000)).getBytes(StandardCharsets.US_ASCII));
        writeBacklen(body, 2 + 4000); // two-byte backlen: entry spans the 127..16383 bucket
        body.write(0xF1);
        body.write(0);
        body.write(0); // 16-bit int 0
        writeBacklen(body, 3);
        body.write(0xF1);
        body.write((byte) 0xFF);
        body.write((byte) 0x7F); // 16-bit int 32767
        writeBacklen(body, 3);
        body.write(0xF2);
        body.write(1);
        body.write(2);
        body.write(3); // 24-bit int
        writeBacklen(body, 4);
        body.write(0xF3);
        body.write(4);
        body.write(3);
        body.write(2);
        body.write(1); // 32-bit int
        writeBacklen(body, 5);
        body.write(0xF4); // 64-bit int LE of -2
        byte[] neg2 = {(byte) 0xFE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        body.writeBytes(neg2);
        writeBacklen(body, 9);
        body.write(0xF0); // 32-bit string
        writeIntLe(body, 100, 4);
        body.writeBytes(("w".repeat(100)).getBytes(StandardCharsets.US_ASCII));
        writeBacklen(body, 105);
        body.write(0xFF);
        writeIntLe(lp, 6 + body.size(), 4);
        writeIntLe(lp, 12, 2);
        lp.writeBytes(body.toByteArray());
        assertEntries(RdbValueDecoder.listpackEntries(lp.toByteArray()),
                "42", "abc", "4095", "-4096", "z".repeat(4000), "0", "32767",
                "" + (1 | (2 << 8) | (3 << 16)),
                "" + (4 | 3 << 8 | 2 << 16 | 1 << 24), "-2", "w".repeat(100));
    }

    @Test
    void decodesIntset() {
        var set = new ByteArrayOutputStream();
        writeIntLe(set, 4, 4); // int32 entries
        writeIntLe(set, 3, 4);
        writeIntLe(set, 7, 4);
        writeIntLe(set, -7, 4);
        writeIntLe(set, Integer.MIN_VALUE, 4);
        assertEntries(RdbValueDecoder.intsetMembers(set.toByteArray()), "7", "-7", "-2147483648");
    }

    @Test
    void decodesZipmapIncludingLongLengths() {
        var zm = new ByteArrayOutputStream();
        zm.write(2); // two entries
        zm.write(3);
        zm.writeBytes("foo".getBytes(StandardCharsets.US_ASCII));
        zm.write(3);
        zm.write(0); // free byte
        zm.writeBytes("bar".getBytes(StandardCharsets.US_ASCII));
        zm.write(254); // 5-byte length: 300-byte key
        writeIntLe(zm, 300, 4);
        zm.writeBytes(("k".repeat(300)).getBytes(StandardCharsets.US_ASCII));
        zm.write(254); // 5-byte value length: 260 bytes
        writeIntLe(zm, 260, 4);
        zm.write(0); // free byte
        zm.writeBytes(("v".repeat(260)).getBytes(StandardCharsets.US_ASCII));
        zm.write(0xFF);
        assertEntries(RdbValueDecoder.zipmapEntries(zm.toByteArray()),
                "foo", "bar", "k".repeat(300), "v".repeat(260));
    }

    @Test
    void rejectsCorruptBlobs() {
        assertThrows(RespProtocolException.class,
                () -> RdbValueDecoder.listpackEntries(
                        new byte[]{6, 0, 0, 0, 0, 0, 0x05, (byte) 0xFF, 1}));
        assertThrows(RespProtocolException.class,
                () -> RdbValueDecoder
                        .ziplistEntries(new byte[]{99, 0, 0, 0, 4, 0, 0, 0, 1, 0, 0, (byte) 0xF3, (byte) 0xFF}));
        assertThrows(RespProtocolException.class,
                () -> RdbValueDecoder.intsetMembers(new byte[]{3, 0, 0, 0, 1, 0, 0, 0}));
        assertThrows(RespProtocolException.class,
                () -> RdbValueDecoder.zipmapEntries(new byte[]{1, 5, 'a'}));
    }

    private static void writeZiplistEntry(ByteArrayOutputStream body, int len, byte marker, int bytes) {
        body.write(0); // prevlen
        if (bytes == 1) {
            body.write(marker | len);
        } else if (bytes == 2) {
            body.write(marker | (len >> 8));
            body.write(len & 0xFF);
        } else {
            throw new IllegalArgumentException();
        }
        body.writeBytes(("y".repeat(len)).getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeZiplistInt(ByteArrayOutputStream body, int marker, long value) {
        body.write(0);
        body.write(marker);
        int width = marker == 0xC0 ? 2 : marker == 0xD0 ? 4 : 8;
        writeIntLe(body, value, width);
    }

    /** listpack backlen: 7 bits per byte, MSB set on all but the last (2-byte case suffices here). */
    private static void writeBacklen(ByteArrayOutputStream body, int entryLen) {
        if (entryLen < 128) {
            body.write(entryLen);
            return;
        }
        body.write(0x80 | (entryLen >>> 7));
        body.write(entryLen & 0x7F);
    }

    private static void writeIntLe(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++)
            out.write((int) (value >>> (8 * i)) & 0xff);
    }

    private static void writeIntBe(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void assertEntries(List<byte[]> actual, String... expected) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++)
            assertArrayEquals(expected[i].getBytes(StandardCharsets.US_ASCII), actual.get(i), "entry " + i);
    }
}
