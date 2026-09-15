package io.github.susongyan.redisops.platform.infrastructure.distribution;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedRespTest {
    private BoundedResp input(String text) {
        return new BoundedResp(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)), () -> {
        });
    }
    @Test
    void parsesPageAndDiscardsOversizedKey() throws Exception {
        var page = input("*2\r\n$1\r\n0\r\n*2\r\n$1\r\na\r\n$4097\r\n" + "x".repeat(4097) + "\r\n").scan();
        assertEquals("0", page.cursor());
        assertArrayEquals(new byte[]{97}, page.keys().get(0));
        assertNull(page.keys().get(1));
    }
    @Test
    void rejectsHugeDeclaredLengthsBeforeAllocating() {
        for (String text : new String[]{"*2\r\n$1\r\n0\r\n*5001\r\n", "*2\r\n$1\r\n0\r\n*1\r\n$999999999999\r\n",
                "*2\r\n$33\r\n"})
            assertThrows(IOException.class, () -> input(text).scan());
    }
    @Test
    void badLastElementDoesNotReturnPartialPage() {
        assertThrows(IOException.class, () -> input("*2\r\n$1\r\n0\r\n*2\r\n$1\r\na\r\n$5\r\nx").scan());
    }
    @Test
    void boundsAggregateResponseAndMetadataDepth() {
        assertThrows(IOException.class,
                () -> input("*2\r\n$1\r\n0\r\n*300\r\n" + ("$4096\r\n" + "x".repeat(4096) + "\r\n").repeat(300))
                        .scan());
        assertThrows(IOException.class, () -> input("*1\r\n".repeat(8) + "+OK\r\n").metadata());
    }
    @Test
    void honorsCancellationAndNeverIncludesRedisErrorText() {
        var cancelled = new BoundedResp(new ByteArrayInputStream(new byte[0]), () -> {
            throw new IllegalStateException("SCAN_TIMEOUT");
        });
        assertThrows(IllegalStateException.class, cancelled::scan);
        var error = assertThrows(IOException.class, () -> input("-ERR private-content\r\n").scan());
        assertFalse(error.toString().contains("private-content"));
    }
}
