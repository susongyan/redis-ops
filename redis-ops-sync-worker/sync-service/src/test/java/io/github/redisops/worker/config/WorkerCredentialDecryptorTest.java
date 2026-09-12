package io.github.redisops.worker.config;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerCredentialDecryptorTest {
    @Test
    void decryptsPlatformCompatibleCiphertextAndRejectsTampering() throws Exception {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        UUID id = UUID.randomUUID();
        byte[] ciphertext = encrypt(id, key, "secret");
        WorkerCredentialDecryptor decryptor = new WorkerCredentialDecryptor(
                "k1:" + Base64.getEncoder().encodeToString(key));

        assertThat(decryptor.decrypt(id, ciphertext, "k1")).containsExactly("secret".toCharArray());
        ciphertext[ciphertext.length - 1] ^= 1;
        assertThatThrownBy(() -> decryptor.decrypt(id, ciphertext, "k1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("credential decryption failed");
        assertThatThrownBy(() -> decryptor.decrypt(id, ciphertext, "missing"))
                .isInstanceOf(IllegalStateException.class).hasMessage("credential encryption key is unavailable");
    }

    private static byte[] encrypt(UUID id, byte[] key, String value) throws Exception {
        byte[] iv = new byte[12];
        java.util.Arrays.fill(iv, (byte) 3);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("redis-credential:" + id).getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
    }
}
