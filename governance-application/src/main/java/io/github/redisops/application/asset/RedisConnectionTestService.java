package io.github.redisops.application.asset;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.ClusterMode;
import io.github.redisops.domain.asset.ClusterRepository;
import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.domain.asset.RedisConnectionProfileProvider;
import io.github.redisops.domain.asset.RedisConnectionTestPort;
import io.github.redisops.domain.asset.RedisConnectionTestResult;
import io.github.redisops.domain.asset.RedisEndpointConfiguration;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
public class RedisConnectionTestService {
    private final ClusterRepository clusters;
    private final RedisConnectionProfileProvider profiles;
    private final RedisConnectionTestPort connectionTest;

    public RedisConnectionTestService(ClusterRepository clusters, RedisConnectionProfileProvider profiles,
                                      RedisConnectionTestPort connectionTest) {
        this.clusters = clusters;
        this.profiles = profiles;
        this.connectionTest = connectionTest;
    }

    public RedisConnectionTestResult test(Long clusterId, ClusterMode mode, String endpoint,
                                          boolean authEnabled, String username, String password) {
        RedisEndpointConfiguration.parse(mode, endpoint);
        if (clusterId != null) {
            clusters.findById(clusterId).orElseThrow(() -> BusinessException.notFound("cluster", clusterId));
        }

        RedisConnectionProfile existing = null;
        char[] plaintext = null;
        try {
            if (authEnabled) {
                if (password != null && !password.isBlank()) {
                    plaintext = password.toCharArray();
                } else if (clusterId != null) {
                    existing = profiles.get(clusterId);
                    if (existing.password() == null)
                        throw new BusinessException("CREDENTIAL_NOT_CONFIGURED",
                                "authentication is enabled but the cluster has no saved password");
                    plaintext = Arrays.copyOf(existing.password(), existing.password().length);
                } else {
                    throw new BusinessException("INVALID_ARGUMENT",
                            "password is required when testing an authenticated cluster");
                }
            }
            return connectionTest.test(mode, endpoint, normalize(username), plaintext);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, '\0');
            if (existing != null) existing.close();
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
