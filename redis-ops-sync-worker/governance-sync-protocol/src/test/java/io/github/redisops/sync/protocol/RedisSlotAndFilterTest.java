package io.github.redisops.sync.protocol;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RedisSlotAndFilterTest {
    @Test
    void honorsHashTags() {
        assertEquals(RedisSlot.of("a{order}:1"), RedisSlot.of("b{order}:2"));
    }
    @Test
    void includeThenExclude() {
        KeyFilter filter = new KeyFilter(List.of("order:*"), List.of("order:debug:*"));
        assertTrue(filter.accepts("order:1".getBytes(StandardCharsets.UTF_8)));
        assertFalse(filter.accepts("order:debug:1".getBytes(StandardCharsets.UTF_8)));
        assertFalse(filter.accepts("user:1".getBytes(StandardCharsets.UTF_8)));
    }
}
