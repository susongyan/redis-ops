package io.github.redisops.infrastructure.alert;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AesGcmNotificationSecretProtectorTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithRandomIvAndBindsCiphertextToChannel() {
        var protector = new AesGcmNotificationSecretProtector("v1:" + KEY);
        UUID channel = UUID.randomUUID();
        var first = protector.encrypt(channel, "https://example.test/hook".toCharArray());
        var second = protector.encrypt(channel, "https://example.test/hook".toCharArray());

        assertNotEquals(Base64.getEncoder().encodeToString(first.ciphertext()),
                Base64.getEncoder().encodeToString(second.ciphertext()));
        assertEquals("https://example.test/hook", new String(protector.decrypt(channel, first.ciphertext(), "v1")));
        assertThrows(RuntimeException.class,
                () -> protector.decrypt(UUID.randomUUID(), first.ciphertext(), "v1"));
    }
}
