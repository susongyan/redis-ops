package io.github.redisops.infrastructure.redis;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.EncryptedSecret;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AesGcmCredentialSecretProtectorTest {
    private static final String KEY_1 = Base64.getEncoder().encodeToString(sequence(1));
    private static final String KEY_2 = Base64.getEncoder().encodeToString(sequence(33));

    @Test
    void encryptsAndDecryptsWithoutEmbeddingPlaintext() {
        var protector = new AesGcmCredentialSecretProtector("v1:" + KEY_1);
        UUID uuid = UUID.randomUUID();
        char[] password = "redis-secret-密码".toCharArray();
        EncryptedSecret encrypted = protector.encrypt(uuid, password);

        assertEquals("v1", encrypted.keyId());
        assertFalse(contains(encrypted.ciphertext(), "redis-secret-密码".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(password, protector.decrypt(uuid, encrypted.ciphertext(), encrypted.keyId()));
    }

    @Test
    void usesRandomIvForEveryEncryption() {
        var protector = new AesGcmCredentialSecretProtector("v1:" + KEY_1);
        UUID uuid = UUID.randomUUID();
        assertFalse(java.util.Arrays.equals(
                protector.encrypt(uuid, "same-password".toCharArray()).ciphertext(),
                protector.encrypt(uuid, "same-password".toCharArray()).ciphertext()));
    }

    @Test
    void rejectsTamperingWrongKeyAndWrongCredentialAad() {
        UUID uuid = UUID.randomUUID();
        var writer = new AesGcmCredentialSecretProtector("v1:" + KEY_1);
        EncryptedSecret encrypted = writer.encrypt(uuid, "secret".toCharArray());
        byte[] tampered = encrypted.ciphertext().clone();
        tampered[tampered.length - 1] ^= 1;

        assertThrows(BusinessException.class, () -> writer.decrypt(uuid, tampered, "v1"));
        assertThrows(BusinessException.class, () -> writer.decrypt(UUID.randomUUID(), encrypted.ciphertext(), "v1"));
        var wrongKey = new AesGcmCredentialSecretProtector("v1:" + KEY_2);
        assertThrows(BusinessException.class, () -> wrongKey.decrypt(uuid, encrypted.ciphertext(), "v1"));
        assertThrows(BusinessException.class, () -> writer.decrypt(uuid, encrypted.ciphertext(), "missing"));
    }

    @Test
    void supportsDualKeyReadAndCurrentKeyRewrite() {
        UUID uuid = UUID.randomUUID();
        var oldOnly = new AesGcmCredentialSecretProtector("v1:" + KEY_1);
        EncryptedSecret oldCiphertext = oldOnly.encrypt(uuid, "rotate-me".toCharArray());
        var keyring = new AesGcmCredentialSecretProtector("v2:" + KEY_2 + ",v1:" + KEY_1);

        char[] plaintext = keyring.decrypt(uuid, oldCiphertext.ciphertext(), oldCiphertext.keyId());
        EncryptedSecret rotated = keyring.encrypt(uuid, plaintext);
        assertEquals("v2", rotated.keyId());
        var currentOnly = new AesGcmCredentialSecretProtector("v2:" + KEY_2);
        assertArrayEquals("rotate-me".toCharArray(), currentOnly.decrypt(uuid, rotated.ciphertext(), rotated.keyId()));
    }

    @Test
    void validatesKeyConfiguration() {
        assertThrows(IllegalStateException.class, () -> new AesGcmCredentialSecretProtector(""));
        assertThrows(IllegalStateException.class, () -> new AesGcmCredentialSecretProtector("v1:YWJj"));
    }

    private static byte[] sequence(int start) {
        byte[] value = new byte[32];
        for (int i = 0; i < value.length; i++)
            value[i] = (byte) (start + i);
        return value;
    }
    private static boolean contains(byte[] source, byte[] target) {
        outer : for (int i = 0; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++)
                if (source[i + j] != target[j])
                    continue outer;
            return true;
        }
        return false;
    }
}
