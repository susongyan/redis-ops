package io.github.redisops.platform.infrastructure.distribution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.redisops.platform.domain.asset.*;
import io.github.redisops.platform.domain.distribution.DistributionScanPort;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DistributionSocketTest {
    @Test
    void blockedReadHonorsCommandDeadline() throws Exception {
        exercise(false);
    }
    @Test
    void interruptedScanClosesBlockedConnection() throws Exception {
        exercise(true);
    }
    private void exercise(boolean interrupt) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var scanning = new CountDownLatch(1);
            var serverFailure = new AtomicReference<Throwable>();
            Thread peer = new Thread(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    new BoundedResp(socket.getInputStream(), () -> {
                    }).metadata(); // ROLE
                    socket.getOutputStream().write("*1\r\n$6\r\nmaster\r\n".getBytes(StandardCharsets.US_ASCII));
                    new BoundedResp(socket.getInputStream(), () -> {
                    }).metadata(); // SCAN
                    scanning.countDown();
                    assertEquals(-1, socket.getInputStream().read()); // caller must close, not retain the socket
                } catch (Throwable e) {
                    serverFailure.set(e);
                }
            });
            peer.setDaemon(true);
            peer.start();
            String endpoint = "127.0.0.1:" + server.getLocalPort();
            var redis = new BoundedDistributionRedis(id -> new RedisConnectionProfile(id, ClusterMode.STANDALONE,
                    List.of(endpoint), null, null, "NONE", null),
                    new DistributionReceiveLimits(1048576, 4096, 5000, 256, 200, 2000, interrupt ? 3000 : 150));
            var failure = new AtomicReference<Throwable>();
            Thread caller = new Thread(() -> {
                try {
                    redis.scan(1, 0, new DistributionScanPort.Shard("node", endpoint), "0", 200);
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            caller.setDaemon(true);
            caller.start();
            assertTrue(scanning.await(2, TimeUnit.SECONDS));
            if (interrupt)
                caller.interrupt();
            caller.join(1500);
            peer.join(1500);
            assertFalse(caller.isAlive(), "scan blocked past safety deadline");
            assertFalse(peer.isAlive(), "connection was not closed");
            assertNull(serverFailure.get());
            assertInstanceOf(IllegalStateException.class, failure.get());
            if (!interrupt)
                assertEquals("SCAN_TIMEOUT", failure.get().getMessage());
        }
    }
}
