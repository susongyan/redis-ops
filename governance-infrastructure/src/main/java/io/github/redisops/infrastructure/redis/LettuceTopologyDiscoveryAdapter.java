package io.github.redisops.infrastructure.redis;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.sentinel.api.StatefulRedisSentinelConnection;
import io.lettuce.core.sentinel.api.sync.RedisSentinelCommands;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
public class LettuceTopologyDiscoveryAdapter implements TopologyDiscoveryPort, RedisConnectionTestPort {
    private final RedisConnectionProfileProvider profiles;
    public LettuceTopologyDiscoveryAdapter(RedisConnectionProfileProvider profiles) {
        this.profiles = profiles;
    }

    @Override
    public List<RedisNode> discover(RedisCluster cluster) {
        try (RedisConnectionProfile profile = profiles.get(cluster.id())) {
            return discover(cluster.id(), profile);
        }
    }

    @Override
    public RedisConnectionTestResult test(ClusterMode mode, String endpoint, String username, char[] password) {
        RedisEndpointConfiguration parsed = RedisEndpointConfiguration.parse(mode, endpoint);
        long started = System.nanoTime();
        try (RedisConnectionProfile profile = new RedisConnectionProfile(0, mode, parsed.seedEndpoints(),
                parsed.sentinelMasterName(), username, username == null ? "PASSWORD" : "ACL", password)) {
            List<RedisNode> nodes = discover(0, profile);
            long elapsedMillis = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            return new RedisConnectionTestResult(true, mode, nodes.size(), elapsedMillis,
                    "Redis connection and required topology commands succeeded");
        } catch (BusinessException exception) {
            if ("INVALID_ENDPOINT".equals(exception.code()))
                throw exception;
            throw connectionFailure(exception);
        } catch (RuntimeException exception) {
            throw connectionFailure(exception);
        }
    }

    private List<RedisNode> discover(long clusterId, RedisConnectionProfile profile) {
        if (profile.mode() == ClusterMode.SENTINEL)
            return discoverSentinel(clusterId, profile);
        RuntimeException last = null;
        for (String endpoint : profile.seedEndpoints())
            try {
                return discoverSeed(clusterId, profile, HostPort.parse(endpoint));
            } catch (RuntimeException e) {
                last = e;
            }
        throw new BusinessException("TOPOLOGY_UNREACHABLE",
                "all Redis seed endpoints failed" + (last == null ? "" : ": " + last.getMessage()), last);
    }

    static BusinessException connectionFailure(RuntimeException error) {
        String message = failureMessages(error);
        if (message.contains("noauth") || message.contains("wrongpass") || message.contains("authentication"))
            return new BusinessException("REDIS_AUTHENTICATION_FAILED",
                    "Redis authentication failed; check the username and password");
        if (message.contains("timeout") || message.contains("connection") || message.contains("refused")
                || message.contains("unresolved") || message.contains("unknown host"))
            return new BusinessException("REDIS_CONNECTION_FAILED",
                    "cannot connect to Redis; check the endpoint, network and firewall");
        return new BusinessException("REDIS_CONFIGURATION_INVALID",
                "Redis is reachable but required topology commands failed; check deployment mode and ACL permissions");
    }

    private static String failureMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && visited.add(current)) {
            if (current.getMessage() != null)
                messages.append(' ').append(current.getMessage());
            current = current.getCause();
        }
        return messages.toString().toLowerCase(Locale.ROOT);
    }

    private List<RedisNode> discoverSeed(long clusterId, RedisConnectionProfile profile, HostPort seed) {
        RedisURI uri = redisUri(seed, profile);
        RedisClient client = RedisClient.create(uri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> commands = connection.sync();
            if (profile.mode() == ClusterMode.CLUSTER)
                return parseClusterNodes(clusterId, commands.clusterNodes());
            Map<String, String> server = parseInfo(commands.info("server"));
            Map<String, String> replication = parseInfo(commands.info("replication"));
            String role = replication.getOrDefault("role", "unknown").toUpperCase(Locale.ROOT);
            return List.of(new RedisNode(null, clusterId, seed.host(), seed.port(), server.get("run_id"), role, null,
                    "[]", null, "CONNECTED"));
        } finally {
            client.shutdown();
        }
    }

    private List<RedisNode> discoverSentinel(long clusterId, RedisConnectionProfile profile) {
        RuntimeException last = null;
        for (String endpoint : profile.seedEndpoints())
            try {
                return discoverSentinelSeed(clusterId, profile, profile.sentinelMasterName(), HostPort.parse(endpoint));
            } catch (RuntimeException e) {
                last = e;
            }
        throw new BusinessException("TOPOLOGY_UNREACHABLE",
                "all Sentinel seed endpoints failed" + (last == null ? "" : ": " + last.getMessage()), last);
    }

    private List<RedisNode> discoverSentinelSeed(long clusterId, RedisConnectionProfile profile, String masterName,
            HostPort seed) {
        RedisURI uri = redisUri(seed, profile);
        RedisClient client = RedisClient.create(uri);
        try (StatefulRedisSentinelConnection<String, String> connection = client.connectSentinel(uri)) {
            RedisSentinelCommands<String, String> commands = connection.sync();
            Map<String, String> master = commands.master(masterName);
            if (master == null || master.isEmpty())
                throw new BusinessException("EMPTY_TOPOLOGY", "Sentinel master not found: " + masterName);
            List<RedisNode> result = new ArrayList<>();
            result.add(sentinelNode(clusterId, master, "MASTER", null));
            String masterId = master.get("runid");
            for (Map<String, String> replica : commands.replicas(masterName))
                result.add(sentinelNode(clusterId, replica, "REPLICA", masterId));
            return result;
        } finally {
            client.shutdown();
        }
    }

    private RedisURI redisUri(HostPort seed, RedisConnectionProfile profile) {
        RedisURI.Builder builder = RedisURI.builder().withHost(seed.host()).withPort(seed.port())
                .withTimeout(Duration.ofSeconds(5));
        if (profile.password() != null) {
            if (profile.username() != null && !profile.username().isBlank())
                builder.withAuthentication(profile.username(), profile.password());
            else
                builder.withPassword(profile.password());
        }
        return builder.build();
    }

    static List<RedisNode> parseClusterNodes(long clusterId, String raw) {
        List<RedisNode> nodes = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            if (line.isBlank())
                continue;
            String[] p = line.trim().split("\\s+");
            if (p.length < 8)
                continue;
            String address = p[1].split("@", 2)[0];
            HostPort hp = HostPort.parse(address);
            String role = p[2].contains("master") ? "MASTER" : p[2].contains("slave") ? "REPLICA" : "UNKNOWN";
            String slots = p.length > 8 ? toJson(Arrays.copyOfRange(p, 8, p.length)) : "[]";
            nodes.add(new RedisNode(null, clusterId, hp.host(), hp.port(), p[0], role, "-".equals(p[3]) ? null : p[3],
                    slots, null,
                    p[2].contains("fail") ? "FAILED" : "CONNECTED"));
        }
        if (nodes.isEmpty())
            throw new BusinessException("EMPTY_TOPOLOGY", "Redis returned no cluster nodes");
        return nodes;
    }
    private static Map<String, String> parseInfo(String raw) {
        Map<String, String> result = new HashMap<>();
        for (String line : raw.split("\\R")) {
            int i = line.indexOf(':');
            if (i > 0 && !line.startsWith("#"))
                result.put(line.substring(0, i), line.substring(i + 1));
        }
        return result;
    }
    private static String toJson(String[] values) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (String value : values)
            if (value.matches("[0-9]+(-[0-9]+)?"))
                joiner.add("\"" + value + "\"");
        return joiner.toString();
    }
    private static RedisNode sentinelNode(long clusterId, Map<String, String> values, String role, String masterId) {
        String host = values.getOrDefault("ip", values.get("address"));
        int port = Integer.parseInt(values.getOrDefault("port", "6379"));
        String flags = values.getOrDefault("flags", "");
        String status = flags.contains("s_down") || flags.contains("o_down") || flags.contains("disconnected")
                ? "FAILED"
                : "CONNECTED";
        return new RedisNode(null, clusterId, host, port, values.get("runid"), role, masterId, "[]", null, status);
    }
    record HostPort(String host, int port) {
        static HostPort parse(String endpoint) {
            String value = endpoint.trim();
            int split = value.lastIndexOf(':');
            if (split < 1)
                throw new BusinessException("INVALID_ENDPOINT", "endpoint must be host:port");
            try {
                return new HostPort(value.substring(0, split).replace("[", "").replace("]", ""),
                        Integer.parseInt(value.substring(split + 1)));
            } catch (NumberFormatException e) {
                throw new BusinessException("INVALID_ENDPOINT", "endpoint port is invalid");
            }
        }
        static List<HostPort> parseAll(String endpoints) {
            if (endpoints == null || endpoints.isBlank())
                throw new BusinessException("INVALID_ENDPOINT", "at least one endpoint is required");
            return Arrays.stream(endpoints.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(HostPort::parse)
                    .toList();
        }
    }
    record SentinelEndpoint(String masterName, List<HostPort> seeds) {
        static SentinelEndpoint parse(String value) {
            int at = value.indexOf('@');
            if (at < 1)
                throw new BusinessException("INVALID_ENDPOINT",
                        "Sentinel endpoint must be masterName@host:port[,host:port]");
            return new SentinelEndpoint(value.substring(0, at), HostPort.parseAll(value.substring(at + 1)));
        }
    }
}
