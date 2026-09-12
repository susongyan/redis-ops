package io.github.redisops.platform.domain.asset;

public record EncryptedSecret(byte[] ciphertext, String keyId) {
}
