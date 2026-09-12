package io.github.redisops.worker.runtime;

import io.github.redisops.worker.domain.WorkerClusterView;
import io.github.redisops.worker.domain.WorkerRedisNode;
import io.github.redisops.worker.persistence.WorkerAssetReadPort;
import io.github.redisops.worker.persistence.WorkerTopologyPort;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class TargetResetter {
    private final WorkerAssetReadPort clusters;
    private final WorkerRedisConnectionProfilePort profiles;
    private final WorkerTopologyPort topology;
    public TargetResetter(WorkerAssetReadPort clusters, WorkerRedisConnectionProfilePort profiles,
            WorkerTopologyPort topology) {
        this.clusters = clusters;
        this.profiles = profiles;
        this.topology = topology;
    }
    public List<ResetResult> flush(long clusterId, int database) {
        WorkerClusterView cluster = clusters.get(clusterId);
        List<ResetResult> results = new ArrayList<>();
        try (WorkerRedisConnectionProfile profile = profiles.get(clusterId)) {
            if (cluster.mode() == WorkerClusterMode.CLUSTER) {
                for (WorkerRedisNode node : topology.discover(cluster))
                    if ("MASTER".equals(node.role()))
                        results.add(flushEndpoint(profile, node.host(), node.port(), 0));
            } else if (cluster.mode() == WorkerClusterMode.SENTINEL) {
                WorkerRedisNode master = topology.discover(cluster).stream().filter(x -> "MASTER".equals(x.role()))
                        .findFirst().orElseThrow();
                results.add(flushEndpoint(profile, master.host(), master.port(), database));
            } else {
                RedisEndpoint endpoint = RedisEndpoint.parse(profile.seedEndpoints().get(0));
                results.add(flushEndpoint(profile, endpoint.host(), endpoint.port(), database));
            }
        }
        return results;
    }
    private ResetResult flushEndpoint(WorkerRedisConnectionProfile profile, String host, int port, int database) {
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
