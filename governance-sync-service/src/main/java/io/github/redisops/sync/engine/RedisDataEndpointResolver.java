package io.github.redisops.sync.engine;

import io.github.redisops.domain.asset.ClusterMode;
import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.sync.protocol.RespCodec;
import io.github.redisops.sync.protocol.RespProtocolException;
import io.github.redisops.sync.protocol.RespValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class RedisDataEndpointResolver {
    private final Duration connectTimeout;

    public RedisDataEndpointResolver(
            @Value("${sync.engine.connect-timeout-ms:10000}") long connectTimeoutMillis) {
        this.connectTimeout = Duration.ofMillis(connectTimeoutMillis);
    }

    public RedisEndpoint resolvePrimary(RedisConnectionProfile profile) throws IOException {
        if (profile.mode() != ClusterMode.SENTINEL)
            return RedisEndpoint.parse(profile.seedEndpoints().get(0));
        IOException last = null;
        for (String seed : profile.seedEndpoints()) {
            try {
                return querySentinel(profile, RedisEndpoint.parse(seed));
            } catch (IOException error) {
                last = error;
            }
        }
        throw new IOException("all Sentinel endpoints failed to resolve master "
                + profile.sentinelMasterName(), last);
    }

    public List<ClusterMaster> resolveClusterMasters(RedisConnectionProfile profile) throws IOException {
        if (profile.mode() != ClusterMode.CLUSTER)
            return List.of(new ClusterMaster(resolvePrimary(profile), null, 0, 16383));
        IOException last = null;
        for (String seed : profile.seedEndpoints()) {
            RedisEndpoint endpoint = RedisEndpoint.parse(seed);
            try {
                return queryClusterSlots(profile, endpoint);
            } catch (IOException error) {
                last = error;
            }
        }
        throw new IOException("all Redis Cluster seed endpoints failed", last);
    }

    private RedisEndpoint querySentinel(RedisConnectionProfile profile, RedisEndpoint sentinel)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(sentinel.host(), sentinel.port()),
                    Math.toIntExact(connectTimeout.toMillis()));
            socket.setSoTimeout(Math.toIntExact(connectTimeout.toMillis()));
            RespCodec codec = new RespCodec(new BufferedInputStream(socket.getInputStream(), 16 * 1024),
                    new BufferedOutputStream(socket.getOutputStream(), 16 * 1024));
            RespValue response = queryMaster(codec, profile.sentinelMasterName());
            if (response instanceof RespValue.Error error
                    && error.value().toLowerCase(java.util.Locale.ROOT).contains("noauth")) {
                authenticate(codec, profile);
                response = queryMaster(codec, profile.sentinelMasterName());
            }
            if (response instanceof RespValue.Error error)
                throw new RespProtocolException("Sentinel master lookup failed: " + error.value());
            return parseMaster(response);
        }
    }

    private List<ClusterMaster> queryClusterSlots(RedisConnectionProfile profile, RedisEndpoint seed)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(seed.host(), seed.port()),
                    Math.toIntExact(connectTimeout.toMillis()));
            socket.setSoTimeout(Math.toIntExact(connectTimeout.toMillis()));
            RespCodec codec = new RespCodec(new BufferedInputStream(socket.getInputStream(), 64 * 1024),
                    new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
            if (profile.password() != null)
                authenticate(codec, profile);
            codec.writeCommand("CLUSTER", "SLOTS");
            RespValue response = codec.read();
            if (response instanceof RespValue.Error error)
                throw new RespProtocolException("CLUSTER SLOTS failed: " + error.value());
            return parseClusterSlots(response, seed.host());
        }
    }

    static List<ClusterMaster> parseClusterSlots(RespValue response, String fallbackHost) {
        if (!(response instanceof RespValue.Array ranges))
            throw new RespProtocolException("CLUSTER SLOTS returned an unexpected response");
        List<ClusterMaster> result = new ArrayList<>();
        for (RespValue value : ranges.values()) {
            if (!(value instanceof RespValue.Array range) || range.values().size() < 3
                    || !(range.values().get(0) instanceof RespValue.IntegerValue start)
                    || !(range.values().get(1) instanceof RespValue.IntegerValue end)
                    || !(range.values().get(2) instanceof RespValue.Array master)
                    || master.values().size() < 2
                    || !(master.values().get(0) instanceof RespValue.Bulk host)
                    || !(master.values().get(1) instanceof RespValue.IntegerValue port))
                throw new RespProtocolException("CLUSTER SLOTS contains an invalid slot range");
            String resolvedHost = new String(host.value(), StandardCharsets.UTF_8);
            if (resolvedHost.isBlank())
                resolvedHost = fallbackHost;
            String nodeId = master.values().size() > 2 && master.values().get(2) instanceof RespValue.Bulk id
                    ? new String(id.value(), StandardCharsets.US_ASCII)
                    : null;
            if (start.value() < 0 || end.value() > 16383 || start.value() > end.value())
                throw new RespProtocolException("CLUSTER SLOTS contains an invalid boundary");
            result.add(new ClusterMaster(new RedisEndpoint(resolvedHost, Math.toIntExact(port.value())),
                    nodeId, Math.toIntExact(start.value()), Math.toIntExact(end.value())));
        }
        result.sort(Comparator.comparingInt(ClusterMaster::slotStart));
        validateSlotCoverage(result);
        return List.copyOf(result);
    }

    private static void validateSlotCoverage(List<ClusterMaster> masters) {
        int expected = 0;
        for (ClusterMaster master : masters) {
            if (master.slotStart() != expected)
                throw new RespProtocolException("Redis Cluster slot ownership has a gap or overlap at " + expected);
            expected = master.slotEnd() + 1;
        }
        if (expected != 16384)
            throw new RespProtocolException("Redis Cluster does not cover all 16384 slots");
    }

    static RedisEndpoint parseMaster(RespValue response) {
        if (!(response instanceof RespValue.Array array) || array.values().size() != 2
                || !(array.values().get(0) instanceof RespValue.Bulk host)
                || !(array.values().get(1) instanceof RespValue.Bulk port))
            throw new RespProtocolException("Sentinel returned no current master");
        try {
            return new RedisEndpoint(new String(host.value(), StandardCharsets.UTF_8),
                    Integer.parseInt(new String(port.value(), StandardCharsets.US_ASCII)));
        } catch (NumberFormatException error) {
            throw new RespProtocolException("Sentinel returned an invalid master port", error);
        }
    }

    private static RespValue queryMaster(RespCodec codec, String masterName) throws IOException {
        codec.writeCommand("SENTINEL", "GET-MASTER-ADDR-BY-NAME", masterName);
        return codec.read();
    }

    private static void authenticate(RespCodec codec, RedisConnectionProfile profile) throws IOException {
        if (profile.password() == null)
            throw new RespProtocolException("Sentinel requires authentication but no password is configured");
        byte[] password = encode(profile.password());
        try {
            if (profile.username() == null || profile.username().isBlank())
                codec.writeCommand(bytes("AUTH"), password);
            else
                codec.writeCommand(bytes("AUTH"), bytes(profile.username()), password);
            RespValue response = codec.read();
            if (!(response instanceof RespValue.Simple simple) || !"OK".equalsIgnoreCase(simple.value()))
                throw new RespProtocolException("Sentinel authentication failed");
        } finally {
            java.util.Arrays.fill(password, (byte) 0);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encode(char[] value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            if (encoded.hasArray())
                java.util.Arrays.fill(encoded.array(), (byte) 0);
            return result;
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IllegalArgumentException("credential cannot be encoded", error);
        }
    }

    public record ClusterMaster(RedisEndpoint endpoint, String nodeId, int slotStart, int slotEnd) {
    }
}
