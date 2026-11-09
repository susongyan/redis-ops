package io.github.susongyan.redisops.worker.protocol;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Pins the ownership contract of {@link RdbEvent}: the parser hands over the only copy of every
 * array and accessors return it directly, so a full-payload copy per access cannot creep back in
 * (large keys would multiply their heap cost). Consumers must treat event arrays as read-only.
 */
class RdbEventTest {

    @Test
    void keyValueAccessorReturnsTheHandedOffArrayWithoutCopying() {
        byte[] key = "key".getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[]{9, 1, 2, 3};
        RdbEvent.KeyValue event = new RdbEvent.KeyValue(0, key, -1, payload);

        assertSame(key, event.key());
        assertSame(payload, event.dumpPayload());
        // Repeated access must stay free of copies too.
        assertSame(event.key(), event.key());
        assertSame(event.dumpPayload(), event.dumpPayload());
    }

    @Test
    void functionLibraryAccessorReturnsTheHandedOffArrayWithoutCopying() {
        byte[] payload = new byte[]{1, 2, 3};
        RdbEvent.FunctionLibrary event = new RdbEvent.FunctionLibrary(payload);

        assertSame(payload, event.payload());
        assertSame(event.payload(), event.payload());
    }
}
