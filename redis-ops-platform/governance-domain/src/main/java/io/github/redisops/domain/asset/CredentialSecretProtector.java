package io.github.redisops.domain.asset;

import java.util.UUID;

public interface CredentialSecretProtector {
    EncryptedSecret encrypt(UUID credentialUuid, char[] password);
    char[] decrypt(UUID credentialUuid, byte[] ciphertext, String keyId);
    String activeKeyId();
}
