package io.github.redisops.sync.worker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Decrypt-only Worker implementation compatible with Platform Redis credential ciphertext. */
@Component
public final class WorkerCredentialDecryptor {
    private static final int IV_BYTES = 12;
    private final Map<String, SecretKeySpec> keys;

    public WorkerCredentialDecryptor(@Value("${redis-ops.credential.keys:}") String configuredKeys) {
        this.keys = parseKeys(configuredKeys);
    }

    public char[] decrypt(UUID credentialUuid, byte[] ciphertext, String keyId) {
        SecretKeySpec key = keys.get(keyId);
        if (key == null)
            throw new IllegalStateException("credential encryption key is unavailable");
        if (ciphertext == null || ciphertext.length <= IV_BYTES)
            throw new IllegalStateException("credential ciphertext is invalid");
        byte[] iv = Arrays.copyOfRange(ciphertext, 0, IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(ciphertext, IV_BYTES, ciphertext.length);
        byte[] plain = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(("redis-credential:" + credentialUuid).getBytes(StandardCharsets.UTF_8));
            plain = cipher.doFinal(encrypted);
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain));
            try {
                char[] result = new char[decoded.remaining()];
                decoded.get(result);
                return result;
            } finally {
                if (decoded.hasArray())
                    Arrays.fill(decoded.array(), '\0');
            }
        } catch (Exception error) {
            throw new IllegalStateException("credential decryption failed", error);
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
            if (plain != null)
                Arrays.fill(plain, (byte) 0);
        }
    }

    private static Map<String, SecretKeySpec> parseKeys(String configured) {
        if (configured == null || configured.isBlank())
            throw new IllegalStateException("redis-ops.credential.keys must contain at least one AES-256 key");
        Map<String, SecretKeySpec> result = new LinkedHashMap<>();
        for (String item : configured.split(",")) {
            int separator = item.indexOf(':');
            if (separator <= 0 || separator == item.length() - 1)
                throw new IllegalStateException("invalid credential key entry");
            String keyId = item.substring(0, separator).trim();
            byte[] material = Base64.getDecoder().decode(item.substring(separator + 1).trim());
            try {
                if (material.length != 32)
                    throw new IllegalStateException("credential key must decode to exactly 32 bytes");
                if (result.putIfAbsent(keyId, new SecretKeySpec(material, "AES")) != null)
                    throw new IllegalStateException("duplicate credential key id: " + keyId);
            } finally {
                Arrays.fill(material, (byte) 0);
            }
        }
        return Map.copyOf(result);
    }
}
