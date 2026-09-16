package io.github.susongyan.redisops.platform.infrastructure.redis;

import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.operation.RedisOperationPort;
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
        return execute(clusterId, database, command, args, 1);
    }
    public OperationResult execute(long clusterId, int database, String command, List<String> args, int keyPosition) {
        try (RedisConnectionProfile p = profiles.get(clusterId)) {
            if (p.mode() == ClusterMode.CLUSTER && keyPosition == 0)
                throw new IllegalArgumentException("CLUSTER_NODE_TARGET_REQUIRED");
            if (p.mode() == ClusterMode.CLUSTER) {
                var resources = ClientResources.builder()
                        .socketAddressResolver(new DemoClusterSocketAddressResolver(p.seedEndpoints()))
                        .nettyCustomizer(responseLimit())
                        .build();
                var c = RedisClusterClient.create(resources, uris(p, database));
                c.setOptions(io.lettuce.core.cluster.ClusterClientOptions.builder()
                        .protocolVersion(io.lettuce.core.protocol.ProtocolVersion.RESP2).autoReconnect(false)
                        .socketOptions(SocketOptions.builder().connectTimeout(java.time.Duration.ofSeconds(2)).build())
                        .build());
                try (StatefulRedisClusterConnection<byte[], byte[]> x = c.connect(ByteArrayCodec.INSTANCE)) {
                    var s = x.sync();
                    return run(s, command, args, keyPosition);
                } finally {
                    c.shutdown();
                    resources.shutdown();
                }
            }
            var resources = ClientResources.builder().nettyCustomizer(responseLimit()).build();
            RedisClient c = RedisClient.create(resources, uri(p.seedEndpoints().get(0), database, p));
            c.setOptions(ClientOptions.builder().protocolVersion(io.lettuce.core.protocol.ProtocolVersion.RESP2)
                    .autoReconnect(false)
                    .socketOptions(SocketOptions.builder().connectTimeout(java.time.Duration.ofSeconds(2)).build())
                    .build());
            try (StatefulRedisConnection<byte[], byte[]> x = c.connect(ByteArrayCodec.INSTANCE)) {
                var s = x.sync();
                return run(s, command, args, keyPosition);
            } finally {
                c.shutdown();
                resources.shutdown();
            }
        } catch (Exception e) {
            return new OperationResult(false, null, null, 0, -1, e.getClass().getSimpleName());
        }
    }
    private static io.lettuce.core.resource.NettyCustomizer responseLimit() {
        return new io.lettuce.core.resource.NettyCustomizer() {
            @Override
            public void afterChannelInitialized(io.netty.channel.Channel channel) {
                channel.pipeline().addFirst(new io.netty.channel.ChannelInboundHandlerAdapter() {
                    private long received;
                    @Override
                    public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object message)
                            throws Exception {
                        if (message instanceof io.netty.buffer.ByteBuf buf
                                && (received += buf.readableBytes()) > 1048576) {
                            io.netty.util.ReferenceCountUtil.release(message);
                            ctx.close();
                            return;
                        }
                        super.channelRead(ctx, message);
                    }
                });
            }
        };
    }
    static OperationResult run(BaseRedisCommands<byte[], byte[]> commands, String command, List<String> args,
            int keyPosition) {
        byte[] name = command.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        io.lettuce.core.protocol.ProtocolKeyword keyword = new io.lettuce.core.protocol.ProtocolKeyword() {
            public byte[] getBytes() {
                return name;
            }
            public String name() {
                return command.toUpperCase(Locale.ROOT);
            }
        };
        var arguments = new io.lettuce.core.protocol.CommandArgs<byte[], byte[]>(ByteArrayCodec.INSTANCE);
        for (int i = 0; i < args.size(); i++) {
            byte[] value = args.get(i).getBytes(StandardCharsets.UTF_8);
            if (i + 1 == keyPosition)
                arguments.addKey(value);
            else
                arguments.add(value);
        }
        var output = new ConsoleCommandOutput();
        commands.dispatch(keyword, output, arguments);
        Object value = normalize(output.result());
        long ttl = -1;
        if (keyPosition > 0 && commands instanceof RedisKeyCommands<?, ?> keys) {
            try {
                ttl = ((RedisKeyCommands<byte[], byte[]>) keys)
                        .ttl(args.get(keyPosition - 1).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                /* Optional observation must not reinterpret a successful command as failed. */ }
        }
        try {
            String text = value instanceof String string
                    ? string
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
            return new OperationResult(true,
                    value == null
                            ? "null"
                            : value instanceof List ? "array" : value instanceof Number ? "number" : "string",
                    text, text.getBytes(StandardCharsets.UTF_8).length, ttl, null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("INVALID_RESPONSE");
        }
    }
    private static Object normalize(Object value) {
        if (value instanceof byte[] bytes)
            return new String(bytes, StandardCharsets.UTF_8);
        if (value instanceof List<?> list)
            return list.stream().map(LettuceRedisOperationAdapter::normalize).toList();
        return value;
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
        u.setTimeout(java.time.Duration.ofSeconds(3));
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
