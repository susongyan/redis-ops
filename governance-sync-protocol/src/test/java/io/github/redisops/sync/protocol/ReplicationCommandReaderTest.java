package io.github.redisops.sync.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplicationCommandReaderTest {
    @Test
    void calculatesOffsetsFromOriginalWireBytes() throws Exception {
        byte[] wire = "*2\r\n$4\r\nINCR\r\n$1\r\nx\r\n".getBytes(StandardCharsets.US_ASCII);
        CountingInputStream input = new CountingInputStream(new ByteArrayInputStream(wire));
        ReplicationCommand command = new ReplicationCommandReader(
                new RespCodec(input, OutputStream.nullOutputStream()), input, 41).read();
        assertEquals("INCR", command.name());
        assertEquals(42, command.startOffset());
        assertEquals(41 + wire.length, command.endOffset());
    }

    @Test
    void rejectsNonArrayItems() {
        byte[] wire = "+PONG\r\n".getBytes(StandardCharsets.US_ASCII);
        CountingInputStream input = new CountingInputStream(new ByteArrayInputStream(wire));
        var reader = new ReplicationCommandReader(new RespCodec(input, OutputStream.nullOutputStream()), input, 0);
        assertThrows(RespProtocolException.class, reader::read);
    }

    @Test
    void ignoresReplicationSeparatorsWithoutMovingOffset() throws Exception {
        byte[] command = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] wire = new byte[command.length + 2];
        wire[0] = '\r';
        wire[1] = '\n';
        System.arraycopy(command, 0, wire, 2, command.length);
        CountingInputStream input = new CountingInputStream(new ByteArrayInputStream(wire));
        ReplicationCommand value = new ReplicationCommandReader(
                new RespCodec(input, OutputStream.nullOutputStream()), input, 100).read();
        assertEquals(101, value.startOffset());
        assertEquals(100 + command.length, value.endOffset());
    }
}
