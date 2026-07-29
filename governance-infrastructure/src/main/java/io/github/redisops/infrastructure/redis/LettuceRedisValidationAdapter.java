package io.github.redisops.infrastructure.redis;

import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.validation.*;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisServerCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class LettuceRedisValidationAdapter implements RedisValidationPort {
    private final RedisConnectionProfileProvider profiles;
    public LettuceRedisValidationAdapter(RedisConnectionProfileProvider profiles) {
        this.profiles = profiles;
    }

    @Override
    public ScanPage scan(long clusterId, int database, String cursor, int count) {
        return withCommands(clusterId, database, commands -> {
            KeyScanCursor<byte[]> page = "0".equals(cursor)
                    ? commands.scan(ScanArgs.Builder.limit(count))
                    : commands.scan(ScanCursor.of(cursor), ScanArgs.Builder.limit(count));
            return new ScanPage(page.getCursor(), page.getKeys().stream().map(ValidationKey::new).toList());
        });
    }

    @Override
    public Optional<ValidationValue> inspect(long clusterId, int database, byte[] key, ValidationTask task) {
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            List<RedisURI> uris = profile.seedEndpoints().stream().map(endpoint -> uri(endpoint, database, profile))
                    .toList();
            if (profile.mode() == ClusterMode.CLUSTER) {
                RedisClusterClient client = RedisClusterClient.create(uris);
                try (StatefulRedisClusterConnection<byte[], byte[]> connection = client
                        .connect(ByteArrayCodec.INSTANCE)) {
                    return inspect(connection.sync(), connection.sync(), connection.sync(), key, task);
                } finally {
                    client.shutdown();
                }
            }
            RedisClient client = RedisClient.create(uris.get(0));
            try (StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE)) {
                return inspect(connection.sync(), connection.sync(), connection.sync(), key, task);
            } finally {
                client.shutdown();
            }
        }
    }
    private Optional<ValidationValue> inspect(RedisKeyCommands<byte[], byte[]> commands,
            RedisServerCommands<byte[], byte[]> server, RedisStringCommands<byte[], byte[]> strings, byte[] key,
            ValidationTask task) {
        String type = commands.type(key);
        if ("none".equals(type))
            return Optional.empty();
        long ttl = normalizeTtl(commands.ttl(key));
        Long memory = server.memoryUsage(key);
        long size = memory == null ? 0 : memory;
        if (size > task.largeKeyThresholdBytes())
            return Optional.of(new ValidationValue(type, size, ttl, null, "METADATA", "LARGE_KEY_THRESHOLD"));
        if (!("string".equals(type) || "hash".equals(type) || "list".equals(type) || "set".equals(type)
                || "zset".equals(type) || "stream".equals(type)))
            return Optional.of(new ValidationValue(type, size, ttl, null, "METADATA", "UNSUPPORTED_TYPE"));
        if (size > task.maxDeepCompareBytes())
            return Optional.of(new ValidationValue(type, size, ttl, null, "METADATA", "MAX_DEEP_COMPARE_BYTES"));
        String digest = "string".equals(type)
                ? stringDigest(strings, key, task.chunkBytes())
                : dumpDigest(commands, type, key, task.maxDeepCompareBytes());
        if (digest == null)
            return Optional.of(new ValidationValue(type, size, ttl, null, "METADATA", "DUMP_LIMIT"));
        String typeAfter = commands.type(key);
        Long memoryAfter = server.memoryUsage(key);
        if (!type.equals(typeAfter) || !Objects.equals(memory, memoryAfter))
            return Optional.of(new ValidationValue(type, size, ttl, null, "METADATA", "CHANGED_DURING_READ"));
        return Optional.of(new ValidationValue(type, size, ttl, digest, "SEMANTIC_DIGEST", null));
    }

    private <T> T withCommands(long clusterId, int database, CommandCallback<T> callback) {
        try (RedisConnectionProfile profile = profiles.get(clusterId)) {
            List<RedisURI> uris = profile.seedEndpoints().stream().map(endpoint -> uri(endpoint, database, profile))
                    .toList();
            if (profile.mode() == ClusterMode.CLUSTER) {
                RedisClusterClient client = RedisClusterClient.create(uris);
                try (StatefulRedisClusterConnection<byte[], byte[]> connection = client
                        .connect(ByteArrayCodec.INSTANCE)) {
                    return callback.execute(connection.sync());
                } finally {
                    client.shutdown();
                }
            }
            RedisClient client = RedisClient.create(uris.get(0));
            try (StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE)) {
                return callback.execute(connection.sync());
            } finally {
                client.shutdown();
            }
        }
    }
    private static RedisURI uri(String endpoint, int database, RedisConnectionProfile profile) {
        int separator = endpoint.lastIndexOf(':');
        if (separator <= 0 || separator == endpoint.length() - 1)
            throw new IllegalArgumentException("invalid Redis endpoint");
        String host = endpoint.substring(0, separator);
        int port = Integer.parseInt(endpoint.substring(separator + 1));
        RedisURI uri = profile.mode() == ClusterMode.SENTINEL
                ? RedisURI.Builder.sentinel(host, port, profile.sentinelMasterName()).withDatabase(database).build()
                : RedisURI.Builder.redis(host, port).withDatabase(database).build();
        if (profile.password() != null) {
            if (profile.username() != null && !profile.username().isBlank())
                uri.setUsername(profile.username());
            uri.setPassword(profile.password());
        }
        return uri;
    }
    private static long normalizeTtl(Long ttl) {
        return ttl == null || ttl < 0 ? -1 : ttl;
    }
    private static String stringDigest(RedisStringCommands<byte[], byte[]> commands, byte[] key, int chunkBytes) {
        Long length = commands.strlen(key);
        if (length == null)
            return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("string".getBytes(StandardCharsets.US_ASCII));
            for (long start = 0; start < length; start += chunkBytes) {
                byte[] chunk = commands.getrange(key, start, Math.min(length - 1, start + chunkBytes - 1));
                digest.update(chunk);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private static String dumpDigest(RedisKeyCommands<byte[], byte[]> commands, String type, byte[] key,
            long maxBytes) {
        byte[] dump = commands.dump(key);
        if (dump == null || dump.length > maxBytes)
            return null;
        return sha256(type.getBytes(StandardCharsets.US_ASCII), dump);
    }
    private static String sha256(byte[]... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] value : values)
                digest.update(value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    @FunctionalInterface
    private interface CommandCallback<T> {
        T execute(RedisKeyCommands<byte[], byte[]> commands);
    }
}
