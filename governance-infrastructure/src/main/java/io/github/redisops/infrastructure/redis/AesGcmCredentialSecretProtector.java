package io.github.redisops.infrastructure.redis;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.CredentialSecretProtector;
import io.github.redisops.domain.asset.EncryptedSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class AesGcmCredentialSecretProtector implements CredentialSecretProtector {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCredentialSecretProtector(
            @Value("${redis-ops.credential.keys:${REDIS_OPS_CREDENTIAL_KEYS:}}") String configuredKeys) {
        this.keys = parseKeys(configuredKeys);
        this.activeKeyId = keys.keySet().iterator().next();
    }

    @Override
    public EncryptedSecret encrypt(UUID credentialUuid, char[] password) {
        if (password == null || password.length == 0)
            throw new BusinessException("INVALID_ARGUMENT", "password is required");
        byte[] plain = encode(password);
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), iv, credentialUuid);
            byte[] encrypted = cipher.doFinal(plain);
            ByteBuffer result = ByteBuffer.allocate(iv.length + encrypted.length);
            result.put(iv).put(encrypted);
            return new EncryptedSecret(result.array(), activeKeyId);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("credential encryption failed", exception);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public char[] decrypt(UUID credentialUuid, byte[] ciphertext, String keyId) {
        SecretKeySpec key = keys.get(keyId);
        if (key == null)
            throw new BusinessException("CREDENTIAL_KEY_UNAVAILABLE", "credential encryption key is unavailable");
        if (ciphertext == null || ciphertext.length <= IV_BYTES)
            throw new BusinessException("CREDENTIAL_DECRYPTION_FAILED", "credential ciphertext is invalid");
        byte[] iv = Arrays.copyOfRange(ciphertext, 0, IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(ciphertext, IV_BYTES, ciphertext.length);
        byte[] plain = null;
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, iv, credentialUuid);
            plain = cipher.doFinal(encrypted);
            return decode(plain);
        } catch (AEADBadTagException exception) {
            throw new BusinessException("CREDENTIAL_DECRYPTION_FAILED", "credential integrity verification failed");
        } catch (GeneralSecurityException exception) {
            throw new BusinessException("CREDENTIAL_DECRYPTION_FAILED", "credential decryption failed");
        } finally {
            Arrays.fill(encrypted, (byte) 0);
            if (plain != null)
                Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public String activeKeyId() {
        return activeKeyId;
    }

    private static Cipher cipher(int mode, SecretKeySpec key, byte[] iv, UUID credentialUuid)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(("redis-credential:" + credentialUuid).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static Map<String, SecretKeySpec> parseKeys(String configuredKeys) {
        if (configuredKeys == null || configuredKeys.isBlank())
            throw new IllegalStateException("REDIS_OPS_CREDENTIAL_KEYS must contain at least one AES-256 key");
        Map<String, SecretKeySpec> result = new LinkedHashMap<>();
        for (String item : configuredKeys.split(",")) {
            String value = item.trim();
            int separator = value.indexOf(':');
            if (separator <= 0 || separator == value.length() - 1)
                throw new IllegalStateException("invalid REDIS_OPS_CREDENTIAL_KEYS entry");
            String keyId = value.substring(0, separator).trim();
            byte[] key;
            try {
                key = Base64.getDecoder().decode(value.substring(separator + 1).trim());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("credential key is not valid base64");
            }
            if (key.length != 32)
                throw new IllegalStateException("credential key must decode to exactly 32 bytes");
            if (result.putIfAbsent(keyId, new SecretKeySpec(key, "AES")) != null)
                throw new IllegalStateException("duplicate credential key id: " + keyId);
        }
        return result;
    }

    private static byte[] encode(char[] chars) {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars));
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            if (buffer.hasArray())
                Arrays.fill(buffer.array(), (byte) 0);
            return result;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("password is not valid UTF-8", exception);
        }
    }

    private static char[] decode(byte[] bytes) {
        try {
            CharBuffer buffer = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            char[] result = new char[buffer.remaining()];
            buffer.get(result);
            if (buffer.hasArray())
                Arrays.fill(buffer.array(), '\0');
            return result;
        } catch (CharacterCodingException exception) {
            throw new BusinessException("CREDENTIAL_DECRYPTION_FAILED", "credential plaintext is invalid");
        }
    }
}
