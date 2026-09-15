package io.github.susongyan.redisops.worker.persistence;

import io.github.susongyan.redisops.worker.runtime.WorkerRedisConnectionProfile;
import io.github.susongyan.redisops.worker.runtime.WorkerRedisConnectionProfilePort;
import io.github.susongyan.redisops.worker.config.WorkerCredentialDecryptor;
import io.github.susongyan.redisops.worker.domain.WorkerClusterView;
import io.github.susongyan.redisops.worker.domain.WorkerEndpointConfiguration;
import org.springframework.stereotype.Repository;

import java.util.Arrays;

/** Worker-owned connection profile provider; credential material never leaves the returned closeable profile. */
@Repository
public class WorkerRedisConnectionProfileProvider implements WorkerRedisConnectionProfilePort {
    private final WorkerAssetMapper mapper;
    private final WorkerAssetReadPort assets;
    private final WorkerCredentialDecryptor decryptor;

    public WorkerRedisConnectionProfileProvider(WorkerAssetMapper mapper, WorkerAssetReadPort assets,
            WorkerCredentialDecryptor decryptor) {
        this.mapper = mapper;
        this.assets = assets;
        this.decryptor = decryptor;
    }

    @Override
    public WorkerRedisConnectionProfile get(long clusterId) {
        WorkerClusterView cluster = assets.get(clusterId);
        WorkerEndpointConfiguration endpoint = WorkerEndpointConfiguration.parse(cluster.mode(), cluster.endpoint());
        WorkerAssetMapper.WorkerCredentialRow secret = mapper.findCredential(clusterId);
        if (secret == null)
            return new WorkerRedisConnectionProfile(clusterId, cluster.mode(), endpoint.seedEndpoints(),
                    endpoint.sentinelMasterName(), null, "NONE", null);
        byte[] ciphertext = secret.encryptedSecret;
        char[] password = null;
        try {
            if (!"ENCRYPTED".equals(secret.secretStatus))
                throw new IllegalStateException("Redis cluster credential is not configured");
            password = decryptor.decrypt(secret.secretUuid, ciphertext, secret.keyId);
            return new WorkerRedisConnectionProfile(clusterId, cluster.mode(), endpoint.seedEndpoints(),
                    endpoint.sentinelMasterName(), secret.username,
                    secret.username == null || secret.username.isBlank() ? "PASSWORD" : "ACL", password);
        } catch (RuntimeException error) {
            if (password != null)
                Arrays.fill(password, '\0');
            throw error;
        } finally {
            if (ciphertext != null)
                Arrays.fill(ciphertext, (byte) 0);
        }
    }
}
