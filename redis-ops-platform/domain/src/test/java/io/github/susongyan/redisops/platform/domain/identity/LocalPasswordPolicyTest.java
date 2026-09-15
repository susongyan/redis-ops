package io.github.susongyan.redisops.platform.domain.identity;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LocalPasswordPolicyTest {
    @Test
    void bcryptLimitUsesUtf8BytesWithoutTruncation() {
        assertDoesNotThrow(() -> LocalPasswordPolicy.validate("a".repeat(6).toCharArray()));
        assertDoesNotThrow(() -> LocalPasswordPolicy.validate("界".repeat(6).toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> LocalPasswordPolicy.validate("界".repeat(5).toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> LocalPasswordPolicy.validate(null));
        assertDoesNotThrow(() -> LocalPasswordPolicy.validate("界".repeat(24).toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> LocalPasswordPolicy.validate("界".repeat(25).toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> LocalPasswordPolicy.validate("a".repeat(5).toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> LocalPasswordPolicy.validate("a".repeat(73).toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> LocalPasswordPolicy.validate(("a".repeat(12) + '\ud800').toCharArray()));
    }
}
