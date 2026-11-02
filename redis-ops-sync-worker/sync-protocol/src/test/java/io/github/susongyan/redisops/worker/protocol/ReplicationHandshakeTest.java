package io.github.susongyan.redisops.worker.protocol;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ReplicationHandshakeTest {
    private static RespCodec codec(String script) {
        return new RespCodec(new ByteArrayInputStream(script.getBytes(StandardCharsets.US_ASCII)),
                new ByteArrayOutputStream());
    }

    @Test
    void skipsMasterKeepaliveNewlinesBeforeRdbBulkMarker() throws Exception {
        var codec = codec("+PONG\r\n+OK\r\n+OK\r\n+FULLRESYNC 549e827feee276880253caf233f872cf4a8a3bb1 0\r\n"
                + "\n\n\n\n$237363688\r\n");
        var reply = new ReplicationHandshake(codec).start(null, null, null, 0);
        assertTrue(reply instanceof ReplicationReply.FullResync);
        assertEquals(237363688L, ((ReplicationReply.FullResync) reply).transfer().length());
    }

    @Test
    void parsesDisklessEofMarkerAfterKeepalives() throws Exception {
        var marker = "1234567890123456789012345678901234567890";
        var codec = codec("+PONG\r\n+OK\r\n+OK\r\n+FULLRESYNC 549e827feee276880253caf233f872cf4a8a3bb1 0\r\n"
                + "\n$EOF:" + marker + "\r\n");
        var reply = new ReplicationHandshake(codec).start(null, null, null, 0);
        assertTrue(reply instanceof ReplicationReply.FullResync);
        assertEquals(marker, ((ReplicationReply.FullResync) reply).transfer().eofMarker());
    }

    @Test
    void classifiesTransientPsyncErrorsForRetry() {
        for (String reply : new String[]{"-NOMASTERLINK Can't SYNC while not connected with my master\r\n",
                "-LOADING Redis is loading the dataset in memory\r\n"})
            assertThrows(TransientReplicationException.class, () -> new ReplicationHandshake(
                    codec("+PONG\r\n+OK\r\n+OK\r\n" + reply)).start(null, null, null, 0));
    }

    @Test
    void keepsOtherPsyncErrorsAsHardFailures() {
        var exception = assertThrows(RespProtocolException.class, () -> new ReplicationHandshake(
                codec("+PONG\r\n+OK\r\n+OK\r\n-ERR unknown command 'PSYNC'\r\n")).start(null, null, null, 0));
        assertFalse(exception instanceof TransientReplicationException);
    }

    @Test
    void reportsUnexpectedMarkerWithItsValue() {
        var codec = codec(
                "+PONG\r\n+OK\r\n+OK\r\n+FULLRESYNC 549e827feee276880253caf233f872cf4a8a3bb1 0\r\n-ERR x\r\n");
        var exception = assertThrows(RespProtocolException.class,
                () -> new ReplicationHandshake(codec).start(null, null, null, 0));
        assertTrue(exception.getMessage().contains("'-'"), exception.getMessage());
    }
}
