package io.github.redisops.infrastructure.redis;

import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.operation.RedisOperationPort;
import io.lettuce.core.*;
import io.lettuce.core.api.sync.*;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.SocketAddressResolver;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class LettuceRedisOperationAdapter implements RedisOperationPort {
    private final RedisConnectionProfileProvider profiles;
    public LettuceRedisOperationAdapter(RedisConnectionProfileProvider profiles) {
        this.profiles = profiles;
    }
    public OperationResult execute(long clusterId, int database, String command, List<String> args) {
        try (RedisConnectionProfile p = profiles.get(clusterId)) {
            if (p.mode() == ClusterMode.CLUSTER) {
                var resources = ClientResources.builder()
                        .socketAddressResolver(new DemoClusterSocketAddressResolver(p.seedEndpoints()))
                        .build();
                var c = RedisClusterClient.create(resources, uris(p, database));
                try (StatefulRedisClusterConnection<byte[], byte[]> x = c.connect(ByteArrayCodec.INSTANCE)) {
                    var s = x.sync();
                    return run(s, s, s, s, s, command, args);
                } finally {
                    c.shutdown();
                    resources.shutdown();
                }
            }
            RedisClient c = RedisClient.create(uri(p.seedEndpoints().get(0), database, p));
            try (StatefulRedisConnection<byte[], byte[]> x = c.connect(ByteArrayCodec.INSTANCE)) {
                var s = x.sync();
                return run(s, s, s, s, s, command, args);
            } finally {
                c.shutdown();
            }
        } catch (Exception e) {
            return new OperationResult(false, null, null, 0, -1, e.getClass().getSimpleName());
        }
    }
    private static OperationResult run(RedisKeyCommands<byte[], byte[]> key, RedisStringCommands<byte[], byte[]> string,
            RedisHashCommands<byte[], byte[]> hash, RedisSetCommands<byte[], byte[]> set,
            RedisSortedSetCommands<byte[], byte[]> sorted, String command, List<String> a) {
        String q = command.toUpperCase(Locale.ROOT);
        byte[] k = a.isEmpty() ? new byte[0] : a.get(0).getBytes(StandardCharsets.UTF_8);
        Object v;
        switch (q) {
            case "GET" -> v = string.get(k);
            case "TTL" -> v = key.ttl(k);
            case "TYPE" -> v = key.type(k);
            case "EXISTS" -> v = key.exists(k);
            case "SET" -> v = string.set(k, b(a, 1));
            case "EXPIRE" -> v = key.expire(k, Long.parseLong(a.get(1)));
            case "PERSIST" -> v = key.persist(k);
            case "HGET" -> v = hash.hget(k, b(a, 1));
            case "HSET" -> v = hash.hset(k, b(a, 1), b(a, 2));
            case "HDEL" -> v = hash.hdel(k, b(a, 1));
            case "SADD" -> v = set.sadd(k, b(a, 1));
            case "SREM" -> v = set.srem(k, b(a, 1));
            case "ZADD" -> v = sorted.zadd(k, Double.parseDouble(a.get(1)), b(a, 2));
            case "ZREM" -> v = sorted.zrem(k, b(a, 1));
            case "UNLINK" -> v = key.unlink(k);
            default -> throw new IllegalArgumentException("OPERATION_UNSUPPORTED");
        }
        if (v instanceof byte[] bytes) {
            long n = bytes.length;
            int shown = Math.min(bytes.length, 4096);
            return new OperationResult(true, "string", new String(bytes, 0, shown, StandardCharsets.UTF_8), n,
                    key.ttl(k), null);
        }
        return new OperationResult(true, v == null ? "null" : v.getClass().getSimpleName(), String.valueOf(v), 0,
                key.ttl(k), null);
    }
    private static byte[] b(List<String> a, int i) {
        return a.get(i).getBytes(StandardCharsets.UTF_8);
    }
    private static List<RedisURI> uris(RedisConnectionProfile p, int db) {
        return p.seedEndpoints().stream().map(x -> uri(x, db, p)).toList();
    }
    private static RedisURI uri(String e, int db, RedisConnectionProfile p) {
        int i = e.lastIndexOf(':');
        RedisURI u = p.mode() == ClusterMode.SENTINEL
                ? RedisURI.Builder
                        .sentinel(e.substring(0, i), Integer.parseInt(e.substring(i + 1)), p.sentinelMasterName())
                        .withDatabase(db).build()
                : RedisURI.Builder.redis(e.substring(0, i), Integer.parseInt(e.substring(i + 1))).withDatabase(db)
                        .build();
        if (p.password() != null) {
            if (p.username() != null && !p.username().isBlank())
                u.setUsername(p.username());
            u.setPassword(p.password());
        }
        return u;
    }

    /** Maps Docker-advertised demo cluster node addresses to the configured host port. */
    private static final class DemoClusterSocketAddressResolver extends SocketAddressResolver {
        private final Map<Integer, String> configuredHosts = new HashMap<>();
        DemoClusterSocketAddressResolver(List<String> endpoints) {
            for (String endpoint : endpoints) {
                int separator = endpoint.lastIndexOf(':');
                if (separator > 0)
                    configuredHosts.put(Integer.parseInt(endpoint.substring(separator + 1)),
                            endpoint.substring(0, separator));
            }
        }
        @Override
        public java.net.SocketAddress resolve(RedisURI uri) {
            String host = configuredHosts.get(uri.getPort());
            if (host != null && !isLocalHost(uri.getHost()) && isLocalHost(host))
                return new InetSocketAddress(host, uri.getPort());
            return super.resolve(uri);
        }
        private static boolean isLocalHost(String host) {
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        }
    }
}
