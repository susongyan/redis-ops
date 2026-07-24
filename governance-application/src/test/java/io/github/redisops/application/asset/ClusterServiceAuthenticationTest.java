package io.github.redisops.application.asset;

import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.audit.AuditRepository;
import io.github.redisops.domain.location.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClusterServiceAuthenticationTest {
    private final ClusterRepository clusters = mock(ClusterRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);
    private final LocationRepository locations = mock(LocationRepository.class);
    private final ClusterSecretRepository secrets = mock(ClusterSecretRepository.class);
    private final CredentialSecretProtector protector = mock(CredentialSecretProtector.class);
    private final ClusterService service = new ClusterService(clusters, audits, locations, secrets, protector);

    @Test
    void createsAuthenticatedClusterWithEncryptedSecret() {
        prepareCreate();
        when(protector.encrypt(any(), any())).thenReturn(new EncryptedSecret(new byte[]{1, 2, 3}, "v1"));

        service.create(command(true, "reader", "secret"), "tester");

        ArgumentCaptor<RedisClusterSecret> saved = ArgumentCaptor.forClass(RedisClusterSecret.class);
        verify(secrets).save(saved.capture());
        assertEquals(7, saved.getValue().clusterId());
        assertEquals("reader", saved.getValue().username());
        assertArrayEquals(new byte[]{1, 2, 3}, saved.getValue().encryptedSecret());
    }

    @Test
    void rejectsAuthenticationWithoutPasswordOnCreate() {
        when(locations.findIdc(1)).thenReturn(Optional.of(idc()));
        assertThrows(RuntimeException.class, () -> service.create(command(true, null, null), "tester"));
        verify(clusters, never()).save(any());
    }

    @Test
    void blankPasswordKeepsExistingSecretOnUpdate() {
        prepareUpdate();
        RedisClusterSecret current = secret("reader");
        when(secrets.findByClusterId(7)).thenReturn(Optional.of(current));

        service.update(7, 0, command(true, "reader", ""), "tester");

        verify(secrets, never()).save(any());
        verify(secrets, never()).deleteByClusterId(anyLong());
    }

    @Test
    void disablingAuthenticationDeletesExistingSecret() {
        prepareUpdate();
        when(secrets.findByClusterId(7)).thenReturn(Optional.of(secret("reader")));

        service.update(7, 0, command(false, null, null), "tester");

        verify(secrets).deleteByClusterId(7);
        verify(audits).append("tester", "CLUSTER_CREDENTIAL_CLEAR", "REDIS_CLUSTER", "7", "SUCCESS");
    }

    private void prepareCreate() {
        when(locations.findIdc(1)).thenReturn(Optional.of(idc()));
        when(clusters.save(any())).thenReturn(cluster());
    }
    private void prepareUpdate() {
        when(locations.findIdc(1)).thenReturn(Optional.of(idc()));
        when(clusters.findById(7)).thenReturn(Optional.of(cluster()));
        when(clusters.update(any(), eq(0L))).thenReturn(true);
    }
    private static ClusterService.UpsertCluster command(boolean auth, String username, String password) {
        return new ClusterService.UpsertCluster("orders", "prod", null, "owner", null, null, ClusterMode.CLUSTER, "7.4",
                "redis:6379", 1L, auth, username, password, ClusterStatus.ACTIVE);
    }
    private static RedisCluster cluster() {
        return new RedisCluster(7L, "orders", "prod", null, "owner", null, null, ClusterMode.CLUSTER, "7.4",
                "redis:6379", 1L, ClusterStatus.ACTIVE, 0, Instant.now(), Instant.now());
    }
    private static RedisClusterSecret secret(String username) {
        return new RedisClusterSecret(7, UUID.randomUUID(), new byte[]{9}, "v1", username, SecretStatus.ENCRYPTED, 0);
    }
    private static Idc idc() {
        return new Idc(1L, "a", "A", 1L, "r", "R", null, ResourceStatus.ACTIVE, null, 0, Instant.now(), Instant.now());
    }
}
