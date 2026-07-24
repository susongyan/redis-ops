package io.github.redisops.sync.protocol;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class RespCodecTest {
    @Test
    void readsBinarySafeArray() throws Exception {
        byte[] input = "*2\r\n$3\r\nSET\r\n$3\r\na\\0b\r\n".replace("\\0", "\0").getBytes(StandardCharsets.ISO_8859_1);
        RespValue.Array result = (RespValue.Array) new RespCodec(new ByteArrayInputStream(input),
                OutputStream.nullOutputStream()).read();
        assertArrayEquals(new byte[]{'a', 0, 'b'}, ((RespValue.Bulk) result.values().get(1)).value());
    }
    @Test
    void writesCommand() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new RespCodec(InputStream.nullInputStream(), output).writeCommand("PING");
        assertEquals("*1\r\n$4\r\nPING\r\n", output.toString(StandardCharsets.US_ASCII));
    }
    @Test
    void rejectsTruncatedBulk() {
        assertThrows(IOException.class,
                () -> new RespCodec(new ByteArrayInputStream("$5\r\nabc".getBytes()), OutputStream.nullOutputStream())
                        .read());
    }
}
