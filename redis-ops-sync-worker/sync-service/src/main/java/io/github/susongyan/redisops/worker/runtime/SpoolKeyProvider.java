package io.github.susongyan.redisops.worker.runtime;

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
    private final SpoolEncryption encryption;

    public SpoolKeyProvider(@Value("${redis-ops.credential.keys:}") String keyRing) {
        this(keyRing, "NONE");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SpoolKeyProvider(@Value("${redis-ops.credential.keys:}") String keyRing,
            @Value("${sync.engine.spool-encryption:NONE}") String encryption) {
        this.masterKey = parseCurrentKey(keyRing);
        this.encryption = SpoolEncryption.parse(encryption);
    }

    public SpoolEncryption encryption() {
        return encryption;
    }

    /**
     * Per-task spool key. Always derived when the credential key ring is configured — even in NONE
     * mode, so legacy AES_GCM spools from before a configuration switch stay readable on recovery.
     * Returns null only when the ring is empty and the spool is written unencrypted.
     */
    public byte[] taskKey(long taskId) {
        if (masterKey == null) {
            if (encryption == SpoolEncryption.NONE)
                return null;
            throw new IllegalStateException("REDIS_OPS_CREDENTIAL_KEYS is required for encrypted sync spool");
        }
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
