package io.github.redisops.infrastructure.alert;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.alert.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keeps outbound webhook material write-only and cryptographically separate from Redis credentials. */
@Component
public class AesGcmNotificationSecretProtector implements NotificationSecretProtector {
    private static final int IV_BYTES = 12;
    private final Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    private final String active;
    private final SecureRandom random = new SecureRandom();

    public AesGcmNotificationSecretProtector(
            @Value("${redis-ops.credential.keys:${REDIS_OPS_CREDENTIAL_KEYS:}}") String configured) {
        if (configured == null || configured.isBlank())
            throw new IllegalStateException("REDIS_OPS_CREDENTIAL_KEYS is required");
        for (String item : configured.split(",")) {
            int split = item.indexOf(':');
            if (split <= 0)
                throw new IllegalStateException("invalid key ring entry");
            byte[] key = Base64.getDecoder().decode(item.substring(split + 1).trim());
            if (key.length != 32)
                throw new IllegalStateException("notification key must be AES-256");
            keys.put(item.substring(0, split).trim(), new SecretKeySpec(key, "AES"));
        }
        active = keys.keySet().iterator().next();
    }
    public ProtectedNotificationSecret encrypt(UUID id, char[] value) {
        if (value == null || value.length == 0)
            throw new BusinessException("INVALID_ARGUMENT", "webhook URL is required");
        byte[] raw = new String(value).getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keys.get(active), iv, id);
            byte[] encrypted = cipher.doFinal(raw);
            byte[] stored = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, stored, 0, iv.length);
            System.arraycopy(encrypted, 0, stored, iv.length, encrypted.length);
            return new ProtectedNotificationSecret(stored, active);
        } catch (Exception e) {
            throw new IllegalStateException("notification encryption failed", e);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }
    public char[] decrypt(UUID id, byte[] stored, String keyId) {
        SecretKeySpec key = keys.get(keyId);
        if (key == null)
            throw new BusinessException("NOTIFICATION_KEY_UNAVAILABLE", "notification key is unavailable");
        if (stored == null || stored.length <= IV_BYTES)
            throw new BusinessException("NOTIFICATION_DECRYPTION_FAILED", "notification config is invalid");
        byte[] iv = Arrays.copyOfRange(stored, 0, IV_BYTES),
                encrypted = Arrays.copyOfRange(stored, IV_BYTES, stored.length);
        try {
            byte[] raw = cipher(Cipher.DECRYPT_MODE, key, iv, id).doFinal(encrypted);
            try {
                return new String(raw, StandardCharsets.UTF_8).toCharArray();
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } catch (Exception e) {
            throw new BusinessException("NOTIFICATION_DECRYPTION_FAILED", "notification config cannot be decrypted");
        } finally {
            Arrays.fill(encrypted, (byte) 0);
        }
    }
    private static Cipher cipher(int mode, SecretKeySpec key, byte[] iv, UUID id) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(("notification-channel:" + id).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
