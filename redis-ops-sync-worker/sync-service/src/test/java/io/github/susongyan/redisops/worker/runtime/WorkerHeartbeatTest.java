package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.persistence.WorkerRegistryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerHeartbeatTest {
    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> build(String version) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        if (version == null)
            when(provider.getIfAvailable()).thenReturn(null);
        else {
            BuildProperties properties = mock(BuildProperties.class);
            when(properties.getVersion()).thenReturn(version);
            when(provider.getIfAvailable()).thenReturn(properties);
        }
        return provider;
    }

    @Test
    void upsertsStableIdentityIpVersionAndPort() {
        WorkerRegistryMapper registry = mock(WorkerRegistryMapper.class);
        WorkerHeartbeat heartbeat = new WorkerHeartbeat(registry, new WorkerIdentity("worker-1", "10.1.2.3", 8081),
                build("0.1.0"));

        heartbeat.beat();

        ArgumentCaptor<Instant> started = ArgumentCaptor.forClass(Instant.class);
        verify(registry).upsert(eq("worker-1"), eq("10.1.2.3"), eq(8081), eq("0.1.0"), started.capture());
        assertNotNull(started.getValue());
    }

    @Test
    void fallsBackToUnknownVersionWhenBuildInfoMissing() {
        WorkerRegistryMapper registry = mock(WorkerRegistryMapper.class);
        WorkerHeartbeat heartbeat = new WorkerHeartbeat(registry, new WorkerIdentity("worker-1", "", 8081),
                build(null));

        heartbeat.beat();

        verify(registry).upsert(eq("worker-1"), any(), anyInt(), eq("unknown"), any(Instant.class));
    }

    @Test
    void heartbeatFailureDoesNotPropagate() {
        WorkerRegistryMapper registry = mock(WorkerRegistryMapper.class);
        when(registry.upsert(anyString(), any(), anyInt(), anyString(), any(Instant.class)))
                .thenThrow(new IllegalStateException("grants missing"));
        WorkerHeartbeat heartbeat = new WorkerHeartbeat(registry, new WorkerIdentity("worker-1", "", 8081),
                build("0.1.0"));

        assertDoesNotThrow(heartbeat::beat);
    }
}
