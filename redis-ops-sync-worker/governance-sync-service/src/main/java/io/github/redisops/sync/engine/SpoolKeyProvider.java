package io.github.redisops.sync.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

@Component
public class SpoolKeyProvider {
    private static final byte[] INFO = "redis-ops/sync-spool/v1".getBytes(StandardCharsets.US_ASCII);
    private final byte[] masterKey;

    public SpoolKeyProvider(@Value("${redis-ops.credential.keys:}") String keyRing) {
        this.masterKey = parseCurrentKey(keyRing);
    }

    public byte[] taskKey(long taskId) {
        if (masterKey == null)
            throw new IllegalStateException("REDIS_OPS_CREDENTIAL_KEYS is required for encrypted sync spool");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            mac.update(INFO);
            mac.update(Long.toString(taskId).getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal();
        } catch (Exception error) {
            throw new IllegalStateException("cannot derive sync spool key", error);
        }
    }

    private static byte[] parseCurrentKey(String keyRing) {
        if (keyRing == null || keyRing.isBlank())
            return null;
        String first = keyRing.split(",", 2)[0].trim();
        int separator = first.indexOf(':');
        if (separator < 1)
            throw new IllegalStateException("invalid Redis credential key ring");
        byte[] key;
        try {
            key = Base64.getDecoder().decode(first.substring(separator + 1));
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("invalid Redis credential key ring", error);
        }
        if (key.length != 32) {
            Arrays.fill(key, (byte) 0);
            throw new IllegalStateException("Redis credential keys must contain 32 bytes");
        }
        return key;
    }
}
