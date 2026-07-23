package io.github.redisops.infrastructure.redis;

import io.github.redisops.domain.asset.ClusterSecretRepository;
import io.github.redisops.domain.asset.CredentialSecretProtector;
import io.github.redisops.domain.asset.EncryptedSecret;
import io.github.redisops.domain.asset.RedisClusterSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
public class CredentialReencryptionService {
    private final ClusterSecretRepository secrets;
    private final CredentialSecretProtector protector;
    private final int batchSize;

    public CredentialReencryptionService(ClusterSecretRepository secrets, CredentialSecretProtector protector,
                                         @Value("${redis-ops.credential.rotation-batch-size:50}") int batchSize) {
        this.secrets = secrets;
        this.protector = protector;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelayString = "${redis-ops.credential.rotation-initial-delay-ms:5000}",
            fixedDelayString = "${redis-ops.credential.rotation-interval-ms:60000}")
    public void rotateBatch() {
        for (RedisClusterSecret secret : secrets.findForReencryption(protector.activeKeyId(), batchSize)) {
            char[] plaintext = protector.decrypt(secret.secretUuid(), secret.encryptedSecret(), secret.keyId());
            try {
                EncryptedSecret rotated = protector.encrypt(secret.secretUuid(), plaintext);
                secrets.rotate(secret.clusterId(), secret.keyId(), rotated.ciphertext(), rotated.keyId());
            } finally {
                Arrays.fill(plaintext, '\0');
            }
        }
    }
}
