package io.github.susongyan.redisops.worker.protocol;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BinaryGlobTest {
    @Test
    void agreesWithFrontendByteLevelVectors() {
        String[][] vectors = {{"biz:*", "biz:order:1", "true"}, {"biz:?", "biz:中", "false"},
                {"biz:???", "biz:中", "true"},
                {"biz:\\*", "biz:*", "true"}, {"biz:\\*", "biz:a", "false"}, {"[ab]", "a", "false"},
                {"[ab]", "[ab]", "true"}, {"a**?b", "axxb", "true"}, {"*a*b", "zaayb", "true"},
                {"a\\", "a\\", "true"}, {"*", "", "true"}, {"?", "", "false"}};
        for (String[] vector : vectors)
            assertEquals(Boolean.parseBoolean(vector[2]),
                    new BinaryGlob(vector[0]).matches(vector[1].getBytes(StandardCharsets.UTF_8)));
        assertTrue(new BinaryGlob("?").matches(new byte[]{(byte) 255}));
    }
    @Test
    void repeatedStarsDoNotCauseRecursiveExplosion() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> assertFalse(
                new BinaryGlob("*a".repeat(100) + "b").matches("a".repeat(1000).getBytes(StandardCharsets.US_ASCII))));
    }
}
