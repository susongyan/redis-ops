package io.github.redisops.sync.engine;

import io.github.redisops.domain.asset.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class TargetResetter {
    private final ClusterRepository clusters;
    private final RedisConnectionProfileProvider profiles;
    private final TopologyDiscoveryPort topology;
    public TargetResetter(ClusterRepository clusters, RedisConnectionProfileProvider profiles,
            TopologyDiscoveryPort topology) {
        this.clusters = clusters;
        this.profiles = profiles;
        this.topology = topology;
    }
    public void flush(long clusterId, int database) {
        RedisCluster cluster = clusters.findById(clusterId).orElseThrow();
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            if (cluster.mode() == ClusterMode.CLUSTER) {
                for (RedisNode node : topology.discover(cluster))
                    if ("MASTER".equals(node.role()))
                        flushEndpoint(profile, node.host(), node.port(), 0);
            } else if (cluster.mode() == ClusterMode.SENTINEL) {
                RedisNode master = topology.discover(cluster).stream().filter(x -> "MASTER".equals(x.role()))
                        .findFirst().orElseThrow();
                flushEndpoint(profile, master.host(), master.port(), database);
            } else {
                String[] endpoint = profile.seedEndpoints().get(0).split(":");
                flushEndpoint(profile, endpoint[0], Integer.parseInt(endpoint[1]), database);
            }
        }
    }
    private void flushEndpoint(RedisConnectionProfile profile, String host, int port, int database) {
        RedisURI.Builder builder = RedisURI.builder().withHost(host).withPort(port).withDatabase(database)
                .withTimeout(Duration.ofSeconds(10));
        if (profile.password() != null) {
            if (profile.username() != null && !profile.username().isBlank())
                builder.withAuthentication(profile.username(), profile.password());
            else
                builder.withPassword(profile.password());
        }
        RedisClient client = RedisClient.create(builder.build());
        try (var connection = client.connect()) {
            connection.sync().flushdb();
        } finally {
            client.shutdown();
        }
    }
}
