package io.github.redisops.platform.infrastructure.distribution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DistributionJsonTest {
    @Test
    void stopsEncodingBeforeUnboundedBufferGrowth() {
        var json = new DistributionJson(new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> json.encode(java.util.List.of("x".repeat(1000)), 32));
        assertEquals("[1,2]", json.encode(java.util.List.of(1, 2), 32));
    }
}
