package io.github.susongyan.redisops.worker.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedReplicationReaderTest {
    @Test
    void rejectsHugeDeclaredBulkArrayNestedAndMalformedLengthsWithoutReadingPayload() {
        for (String frame : new String[]{"*2\r\n$3\r\nSET\r\n$2147483647\r\n", "*65537\r\n",
                "*1\r\n*1\r\n", "*1\r\n$-1\r\n", "*999999999999999999\r\n", "*-1\r\n"}) {
            var reader = reader(frame, 1024);
            assertThrows(RespProtocolException.class, reader::read);
            assertEquals(10, reader.offset());
        }
    }
    @Test
    void enforcesTotalEncodedBudgetAndPreservesOffsets() throws Exception {
        String frame = "*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\nb\r\n";
        assertThrows(RespProtocolException.class, reader(frame, 26)::read);
        var reader = reader("\n" + frame, 27);
        var command = reader.read();
        assertEquals(11, command.startOffset());
        assertEquals(37, command.endOffset());
        assertEquals(27, command.encodedBytes());
    }
    private ReplicationCommandReader reader(String frame, int bytes) {
        var input = new CountingInputStream(new ByteArrayInputStream(frame.getBytes(StandardCharsets.US_ASCII)));
        return new ReplicationCommandReader(new RespCodec(input, OutputStream.nullOutputStream()), input, 10, bytes,
                65_536);
    }
}
