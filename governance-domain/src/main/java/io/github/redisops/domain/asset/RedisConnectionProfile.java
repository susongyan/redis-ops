package io.github.redisops.domain.asset;

import java.util.Arrays;
import java.util.List;

public final class RedisConnectionProfile implements AutoCloseable {
    private final long clusterId;
    private final ClusterMode mode;
    private final List<String> seedEndpoints;
    private final String sentinelMasterName;
    private final String username;
    private final String authType;
    private final char[] password;

    public RedisConnectionProfile(long clusterId, ClusterMode mode, List<String> seedEndpoints,
            String sentinelMasterName, String username, String authType,
            char[] password) {
        this.clusterId = clusterId;
        this.mode = mode;
        this.seedEndpoints = List.copyOf(seedEndpoints);
        this.sentinelMasterName = sentinelMasterName;
        this.username = username;
        this.authType = authType;
        this.password = password;
    }

    public long clusterId() {
        return clusterId;
    }
    public ClusterMode mode() {
        return mode;
    }
    public List<String> seedEndpoints() {
        return seedEndpoints;
    }
    public String sentinelMasterName() {
        return sentinelMasterName;
    }
    public String username() {
        return username;
    }
    public String authType() {
        return authType;
    }
    public char[] password() {
        return password;
    }

    @Override
    public void close() {
        if (password != null)
            Arrays.fill(password, '\0');
    }
}
