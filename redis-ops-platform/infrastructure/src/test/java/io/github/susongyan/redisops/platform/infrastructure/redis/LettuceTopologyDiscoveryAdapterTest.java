package io.github.susongyan.redisops.platform.infrastructure.redis;

import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.asset.RedisNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LettuceTopologyDiscoveryAdapterTest {
    @Test
    void rejectsPasswordlessServerAuthErrorDuringRealClientHandshake() throws Exception {
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10000);
            var received = new java.util.concurrent.CompletableFuture<String>();
            var peer = new Thread(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8));
                    int count = Integer.parseInt(reader.readLine().substring(1));
                    String command = null;
                    for (int i = 0; i < count; i++) {
                        reader.readLine();
                        String argument = reader.readLine();
                        if (i == 0)
                            command = argument;
                    }
                    received.complete(command);
                    socket.getOutputStream().write(("-ERR AUTH <password> called without any password configured "
                            + "for the default user.\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (Exception e) {
                    received.completeExceptionally(e);
                }
            });
            peer.setDaemon(true);
            peer.start();
            var adapter = new LettuceTopologyDiscoveryAdapter(null);
            var error = assertThrows(BusinessException.class,
                    () -> adapter.test(io.github.susongyan.redisops.platform.domain.asset.ClusterMode.STANDALONE,
                            "127.0.0.1:" + server.getLocalPort(), null, "test-only".toCharArray()));
            assertEquals("REDIS_AUTHENTICATION_FAILED", error.code());
            assertEquals("AUTH", received.get(10, java.util.concurrent.TimeUnit.SECONDS));
            peer.join(1000);
        }
    }

    @Test
    void usesExplicitAuthHandshakeLikeConsole() {
        var options = LettuceTopologyDiscoveryAdapter.connectionOptions();
        assertEquals(io.lettuce.core.protocol.ProtocolVersion.RESP2, options.getProtocolVersion());
        assertFalse(options.isAutoReconnect());
    }

    @Test
    void classifiesPasswordSuppliedToPasswordlessServerAsAuthenticationFailure() {
        var failure = new RuntimeException("Unable to connect to Redis", new RuntimeException(
                "ERR AUTH <password> called without any password configured for the default user."));
        assertEquals("REDIS_AUTHENTICATION_FAILED",
                LettuceTopologyDiscoveryAdapter.connectionFailure(failure).code());
    }

    @Test
    void parsesClusterNodesAndSlots() {
        String raw = "aaa 10.0.0.1:6379@16379 master - 0 0 1 connected 0-8191\n" +
                "bbb 10.0.0.2:6379@16379 slave aaa 0 0 2 connected\n";
        List<RedisNode> nodes = LettuceTopologyDiscoveryAdapter.parseClusterNodes(9, raw);
        assertEquals(2, nodes.size());
        assertEquals("MASTER", nodes.get(0).role());
        assertEquals("[\"0-8191\"]", nodes.get(0).slotRanges());
        assertEquals("aaa", nodes.get(1).masterNodeId());
    }
    @Test
    void parsesSentinelEndpoint() {
        var endpoint = LettuceTopologyDiscoveryAdapter.SentinelEndpoint
                .parse("orders-master@127.0.0.1:26379,127.0.0.2:26379");
        assertEquals("orders-master", endpoint.masterName());
        assertEquals(2, endpoint.seeds().size());
        assertEquals(26379, endpoint.seeds().get(0).port());
    }
    @Test
    void parsesMultipleRedisSeeds() {
        var seeds = LettuceTopologyDiscoveryAdapter.HostPort.parseAll("10.0.0.1:6379, 10.0.0.2:6380");
        assertEquals(2, seeds.size());
        assertEquals("10.0.0.2", seeds.get(1).host());
    }

    @Test
    void classifiesAuthenticationFailureFromNestedLettuceCause() {
        RuntimeException lettuceFailure = new RuntimeException("Unable to connect to Redis",
                new RuntimeException("WRONGPASS invalid username-password pair"));
        RuntimeException failure = new BusinessException("TOPOLOGY_UNREACHABLE",
                "all Redis seed endpoints failed", lettuceFailure);

        BusinessException result = LettuceTopologyDiscoveryAdapter.connectionFailure(failure);

        assertEquals("REDIS_AUTHENTICATION_FAILED", result.code());
        assertFalse(result.getMessage().contains("wrong-password"));
    }
}
