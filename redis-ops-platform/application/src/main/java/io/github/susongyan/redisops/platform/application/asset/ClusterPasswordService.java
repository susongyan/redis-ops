package io.github.susongyan.redisops.platform.application.asset;

import io.github.susongyan.redisops.platform.domain.asset.RedisConnectionProfileProvider;
import org.springframework.stereotype.Service;

@Service
public class ClusterPasswordService {
    private final RedisConnectionProfileProvider profiles;

    public ClusterPasswordService(RedisConnectionProfileProvider profiles) {
        this.profiles = profiles;
    }

    public String read(long clusterId) {
        try (var profile = profiles.get(clusterId)) {
            return profile.password() == null ? null : new String(profile.password());
        }
    }
}
