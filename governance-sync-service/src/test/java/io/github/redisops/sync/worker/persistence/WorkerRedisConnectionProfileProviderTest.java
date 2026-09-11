package io.github.redisops.sync.worker.persistence;

import io.github.redisops.sync.engine.WorkerClusterMode;
import io.github.redisops.sync.engine.WorkerRedisConnectionProfile;
import io.github.redisops.sync.worker.config.WorkerCredentialDecryptor;
import io.github.redisops.sync.worker.domain.WorkerClusterStatus;
import io.github.redisops.sync.worker.domain.WorkerClusterView;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkerRedisConnectionProfileProviderTest {
    private final WorkerAssetMapper mapper = mock(WorkerAssetMapper.class);
    private final WorkerAssetReadPort assets = mock(WorkerAssetReadPort.class);
    private final WorkerCredentialDecryptor decryptor = mock(WorkerCredentialDecryptor.class);
    private final WorkerRedisConnectionProfileProvider provider = new WorkerRedisConnectionProfileProvider(mapper,
            assets, decryptor);

    @Test
    void returnsPasswordlessProfileWhenSecretIsAbsent() {
        when(assets.get(7)).thenReturn(cluster());

        try (WorkerRedisConnectionProfile profile = provider.get(7)) {
            assertThat(profile.authType()).isEqualTo("NONE");
            assertThat(profile.password()).isNull();
        }
    }

    @Test
    void rejectsCredentialThatIsNotEncrypted() {
        when(assets.get(7)).thenReturn(cluster());
        byte[] ciphertext = new byte[]{1, 2, 3};
        WorkerAssetMapper.WorkerCredentialRow row = credential("UNCONFIGURED", ciphertext);
        when(mapper.findCredential(7)).thenReturn(row);

        assertThatThrownBy(() -> provider.get(7)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
        assertThat(ciphertext).containsOnly((byte) 0);
        verifyNoInteractions(decryptor);
    }

    @Test
    void clearsCiphertextAndProfilePassword() {
        when(assets.get(7)).thenReturn(cluster());
        byte[] ciphertext = new byte[]{1, 2, 3};
        WorkerAssetMapper.WorkerCredentialRow row = credential("ENCRYPTED", ciphertext);
        when(mapper.findCredential(7)).thenReturn(row);
        when(decryptor.decrypt(row.secretUuid, ciphertext, "k1")).thenReturn("secret".toCharArray());

        WorkerRedisConnectionProfile profile = provider.get(7);
        char[] password = profile.password();
        assertThat(password).containsExactly("secret".toCharArray());
        assertThat(ciphertext).containsOnly((byte) 0);
        profile.close();
        assertThat(password).containsOnly('\0');
    }

    private static WorkerClusterView cluster() {
        return new WorkerClusterView(7, WorkerClusterMode.STANDALONE, "7.2", "redis:6379",
                WorkerClusterStatus.ACTIVE);
    }

    private static WorkerAssetMapper.WorkerCredentialRow credential(String status, byte[] ciphertext) {
        WorkerAssetMapper.WorkerCredentialRow row = new WorkerAssetMapper.WorkerCredentialRow();
        row.secretUuid = UUID.randomUUID();
        row.encryptedSecret = ciphertext;
        row.keyId = "k1";
        row.username = "user";
        row.secretStatus = status;
        return row;
    }
}
