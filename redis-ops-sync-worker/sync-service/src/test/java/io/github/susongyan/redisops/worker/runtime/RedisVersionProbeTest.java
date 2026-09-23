package io.github.susongyan.redisops.worker.runtime;

import static org.junit.jupiter.api.Assertions.*;
import io.github.susongyan.redisops.worker.protocol.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class RedisVersionProbeTest {
    private String probe(String serverResponse, String replicationResponse, boolean authenticated) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);
            var future = pool.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var codec = new RespCodec(socket.getInputStream(), socket.getOutputStream());
                    if (authenticated) {
                        assertEquals("AUTH", verb(codec.read()));
                        socket.getOutputStream().write("+OK\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    assertEquals("INFO", verb(codec.read()));
                    socket.getOutputStream().write(serverResponse.getBytes(StandardCharsets.US_ASCII));
                    if (replicationResponse != null) {
                        assertEquals("INFO", verb(codec.read()));
                        socket.getOutputStream().write(replicationResponse.getBytes(StandardCharsets.US_ASCII));
                    }
                    return null;
                }
            });
            try (var profile = new WorkerRedisConnectionProfile(1, WorkerClusterMode.STANDALONE,
                    List.of("unused:6379"), null, authenticated ? "test-user" : null, "ACL",
                    authenticated ? "test-only".toCharArray() : null)) {
                try {
                    return new RedisDataEndpointResolver(1000).readServerVersion(profile,
                            new RedisEndpoint(InetAddress.getLoopbackAddress().getHostAddress(),
                                    server.getLocalPort()));
                } finally {
                    future.get(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
    private static String verb(RespValue value) {
        return new String(((RespValue.Bulk) ((RespValue.Array) value).values().get(0)).value(),
                StandardCharsets.US_ASCII);
    }
    private static String bulk(String value) {
        return "$" + value.length() + "\r\n" + value + "\r\n";
    }
    @Test
    void readsVersionWithAuthenticationAndConfirmsMaster() throws Exception {
        assertEquals("5.0.4", probe(bulk("# Server\r\nredis_version:5.0.4\r\n"),
                bulk("role:master\r\n"), true));
    }
    @Test
    void rejectsDeniedMissingMalformedAndReplicaResponses() {
        for (String response : List.of("-NOPERM private-data\r\n", bulk("run_id:abc\r\n"),
                bulk("redis_version:garbage\r\n"))) {
            var error = assertThrows(RespProtocolException.class, () -> probe(response, null, false));
            assertFalse(error.getMessage().contains("private-data"));
        }
        assertThrows(RespProtocolException.class, () -> probe(bulk("redis_version:7.2.1\r\n"),
                bulk("role:slave\r\n"), false));
    }
}
