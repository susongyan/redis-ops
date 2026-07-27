package io.github.redisops.sync.engine;

import io.github.redisops.domain.asset.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    public List<ResetResult> flush(long clusterId, int database) {
        RedisCluster cluster = clusters.findById(clusterId).orElseThrow();
        List<ResetResult> results = new ArrayList<>();
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            if (cluster.mode() == ClusterMode.CLUSTER) {
                for (RedisNode node : topology.discover(cluster))
                    if ("MASTER".equals(node.role()))
                        results.add(flushEndpoint(profile, node.host(), node.port(), 0));
            } else if (cluster.mode() == ClusterMode.SENTINEL) {
                RedisNode master = topology.discover(cluster).stream().filter(x -> "MASTER".equals(x.role()))
                        .findFirst().orElseThrow();
                results.add(flushEndpoint(profile, master.host(), master.port(), database));
            } else {
                RedisEndpoint endpoint = RedisEndpoint.parse(profile.seedEndpoints().get(0));
                results.add(flushEndpoint(profile, endpoint.host(), endpoint.port(), database));
            }
        }
        return results;
    }
    private ResetResult flushEndpoint(RedisConnectionProfile profile, String host, int port, int database) {
        String endpoint = host + ":" + port;
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
            return new ResetResult(endpoint, database, true, null, Instant.now());
        } catch (RuntimeException error) {
            return new ResetResult(endpoint, database, false, safe(error), Instant.now());
        } finally {
            client.shutdown();
        }
    }
    private static String safe(Throwable error) {
        String message = error.getMessage();
        if (message == null)
            message = error.getClass().getSimpleName();
        return message.substring(0, Math.min(message.length(), 512));
    }
    public record ResetResult(String endpoint, int database, boolean success, String error, Instant completedAt) {
    }
}
