package io.github.susongyan.redisops.worker.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

class WorkerIdentityTest {
    @Test
    void trimsConfiguredInstanceName() {
        assertEquals("worker-1", new WorkerIdentity("  worker-1  ", "", 8081).instanceName());
    }

    @Test
    void fallsBackToLocalSyncWhenInstanceNameBlank() {
        assertEquals("local-sync", new WorkerIdentity("  ", "", 8081).instanceName());
    }

    @Test
    void autoDetectsWorkerIpWhenBlank() {
        String ip = new WorkerIdentity("worker-1", "", 8081).workerIp();
        // Auto-detection may legitimately fail on exotic hosts (returns null); when it
        // resolves it must not be a loopback address.
        if (ip != null)
            assertFalse(ip.startsWith("127."));
    }

    @Test
    void rejectsNonLiteralConfiguredIp() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerIdentity("worker-1", "not-an-ip", 8081));
    }

    @Test
    @EnabledIfSystemProperty(named = "worker.identity.loopback.test", matches = "true")
    void rejectsLoopbackConfiguredIp() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerIdentity("worker-1", "127.0.0.1", 8081));
    }

    @Test
    void carriesServerPortForRegistryDisplay() {
        assertEquals(8081, new WorkerIdentity("worker-1", "", 8081).workerPort());
    }
}
