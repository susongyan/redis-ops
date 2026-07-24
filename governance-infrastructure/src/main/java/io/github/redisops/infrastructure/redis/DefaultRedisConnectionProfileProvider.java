package io.github.redisops.infrastructure.redis;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.*;
import org.springframework.stereotype.Component;

@Component
public class DefaultRedisConnectionProfileProvider implements RedisConnectionProfileProvider {
    private final ClusterRepository clusters;
    private final ClusterSecretRepository secrets;
    private final CredentialSecretProtector protector;

    public DefaultRedisConnectionProfileProvider(ClusterRepository clusters, ClusterSecretRepository secrets,
            CredentialSecretProtector protector) {
        this.clusters = clusters;
        this.secrets = secrets;
        this.protector = protector;
    }

    @Override
    public RedisConnectionProfile get(long clusterId) {
        RedisCluster cluster = clusters.findById(clusterId)
                .orElseThrow(() -> BusinessException.notFound("cluster", clusterId));
        RedisEndpointConfiguration endpoint = RedisEndpointConfiguration.parse(cluster.mode(), cluster.endpoint());
        RedisClusterSecret secret = secrets.findByClusterId(clusterId).orElse(null);
        if (secret == null)
            return new RedisConnectionProfile(clusterId, cluster.mode(), endpoint.seedEndpoints(),
                    endpoint.sentinelMasterName(), null, "NONE", null);
        if (secret.secretStatus() != SecretStatus.ENCRYPTED)
            throw new BusinessException("CREDENTIAL_NOT_CONFIGURED", "Redis cluster password is not configured");
        char[] password = protector.decrypt(secret.secretUuid(), secret.encryptedSecret(), secret.keyId());
        return new RedisConnectionProfile(clusterId, cluster.mode(), endpoint.seedEndpoints(),
                endpoint.sentinelMasterName(), secret.username(), secret.authType(), password);
    }
}
