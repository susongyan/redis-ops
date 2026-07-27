package io.github.redisops.sync.protocol;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RdbStreamParserTest {
    @Test
    void parsesBinaryStringAndBuildsDumpPayload() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write("REDIS0009".getBytes(StandardCharsets.US_ASCII));
        bytes.write(254);
        bytes.write(0); // SELECTDB 0
        bytes.write(0); // string type
        bytes.write(3);
        bytes.write("key".getBytes(StandardCharsets.US_ASCII));
        bytes.write(5);
        bytes.write(new byte[]{'a', 0, 'b', 'c', 'd'});
        bytes.write(255);
        bytes.write(new byte[8]);
        List<RdbEvent> events = new ArrayList<>();
        new RdbStreamParser(new ByteArrayInputStream(bytes.toByteArray())).parse(events::add);
        RdbEvent.KeyValue value = (RdbEvent.KeyValue) events.get(1);
        assertArrayEquals(new byte[]{'a', 0, 'b', 'c', 'd'},
                java.util.Arrays.copyOfRange(value.dumpPayload(), 2, 7));
        assertEquals(RdbEvent.End.INSTANCE, events.get(2));
    }
    @Test
    void blocksModuleValues() {
        byte[] bytes = "REDIS0009".getBytes(StandardCharsets.US_ASCII);
        byte[] input = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        input[input.length - 1] = 6;
        assertThrows(UnsupportedRdbTypeException.class,
                () -> new RdbStreamParser(new ByteArrayInputStream(input)).parse(x -> {
                }));
    }

    @Test
    void rejectsChecksumMismatch() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write("REDIS0009".getBytes(StandardCharsets.US_ASCII));
        bytes.write(255);
        long crc = RedisCrc64.calculate(bytes.toByteArray());
        for (int i = 0; i < 8; i++)
            bytes.write((int) (crc >>> (8 * i)) & 0xff);
        byte[] corrupted = bytes.toByteArray();
        corrupted[5] ^= 1;
        assertThrows(RespProtocolException.class,
                () -> new RdbStreamParser(new ByteArrayInputStream(corrupted)).parse(x -> {
                }));
    }
}
