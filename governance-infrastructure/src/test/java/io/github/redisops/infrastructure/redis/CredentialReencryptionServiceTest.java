package io.github.redisops.infrastructure.redis;

import io.github.redisops.domain.asset.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CredentialReencryptionServiceTest {
    @Test void rewritesOldCiphertextWithActiveKey() {
        String oldKey = Base64.getEncoder().encodeToString(bytes(1));
        String currentKey = Base64.getEncoder().encodeToString(bytes(65));
        UUID uuid = UUID.randomUUID();
        var oldProtector = new AesGcmCredentialSecretProtector("v1:" + oldKey);
        EncryptedSecret old = oldProtector.encrypt(uuid, "rotate-secret".toCharArray());
        RedisClusterSecret secret = new RedisClusterSecret(12L, uuid, old.ciphertext(), "v1",
                null, SecretStatus.ENCRYPTED, 0);
        ClusterSecretRepository repository = mock(ClusterSecretRepository.class);
        when(repository.findForReencryption("v2", 10)).thenReturn(List.of(secret));
        var keyring = new AesGcmCredentialSecretProtector("v2:" + currentKey + ",v1:" + oldKey);

        new CredentialReencryptionService(repository, keyring, 10).rotateBatch();

        ArgumentCaptor<byte[]> ciphertext = ArgumentCaptor.forClass(byte[].class);
        verify(repository).rotate(eq(12L), eq("v1"), ciphertext.capture(), eq("v2"));
        var currentOnly = new AesGcmCredentialSecretProtector("v2:" + currentKey);
        assertArrayEquals("rotate-secret".toCharArray(), currentOnly.decrypt(uuid, ciphertext.getValue(), "v2"));
    }

    private static byte[] bytes(int start) { byte[] value=new byte[32];for(int i=0;i<value.length;i++)value[i]=(byte)(start+i);return value; }
}
