package io.github.redisops.worker.persistence;

import io.github.redisops.worker.runtime.WorkerClusterMode;
import io.github.redisops.worker.runtime.WorkerRedisConnectionProfile;
import io.github.redisops.worker.config.WorkerCredentialDecryptor;
import io.github.redisops.worker.domain.WorkerClusterStatus;
import io.github.redisops.worker.domain.WorkerClusterView;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkerRedisConnectionProfileProviderTest {
    private static final byte[] KEY = new byte[32];
    private final WorkerAssetMapper mapper = mock(WorkerAssetMapper.class);
    private final WorkerAssetReadPort assets = mock(WorkerAssetReadPort.class);
    private final WorkerCredentialDecryptor decryptor = new WorkerCredentialDecryptor(
            "k1:" + Base64.getEncoder().encodeToString(KEY));
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
    }

    @Test
    void clearsCiphertextAndProfilePassword() throws Exception {
        when(assets.get(7)).thenReturn(cluster());
        UUID secretUuid = UUID.randomUUID();
        byte[] ciphertext = encrypt(secretUuid, "secret");
        WorkerAssetMapper.WorkerCredentialRow row = credential(secretUuid, "ENCRYPTED", ciphertext);
        when(mapper.findCredential(7)).thenReturn(row);

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
        return credential(UUID.randomUUID(), status, ciphertext);
    }

    private static WorkerAssetMapper.WorkerCredentialRow credential(UUID secretUuid, String status,
            byte[] ciphertext) {
        WorkerAssetMapper.WorkerCredentialRow row = new WorkerAssetMapper.WorkerCredentialRow();
        row.secretUuid = secretUuid;
        row.encryptedSecret = ciphertext;
        row.keyId = "k1";
        row.username = "user";
        row.secretStatus = status;
        return row;
    }

    private static byte[] encrypt(UUID secretUuid, String value) throws Exception {
        byte[] iv = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("redis-credential:" + secretUuid).getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
    }
}
