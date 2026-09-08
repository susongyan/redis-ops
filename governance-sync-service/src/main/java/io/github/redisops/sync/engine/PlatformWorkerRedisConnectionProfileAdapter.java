package io.github.redisops.sync.engine;

import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.domain.asset.RedisConnectionProfileProvider;
import org.springframework.stereotype.Component;

@Component
public final class PlatformWorkerRedisConnectionProfileAdapter implements WorkerRedisConnectionProfilePort {
    private final RedisConnectionProfileProvider delegate;

    public PlatformWorkerRedisConnectionProfileAdapter(RedisConnectionProfileProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public WorkerRedisConnectionProfile get(long clusterId) {
        RedisConnectionProfile profile = delegate.get(clusterId);
        try {
            char[] password = profile.password() == null ? null : profile.password().clone();
            return new WorkerRedisConnectionProfile(profile.clusterId(),
                    WorkerClusterMode.valueOf(profile.mode().name()),
                    profile.seedEndpoints(), profile.sentinelMasterName(), profile.username(), profile.authType(),
                    password);
        } finally {
            profile.close();
        }
    }
}
