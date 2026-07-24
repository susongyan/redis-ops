package io.github.redisops.domain.asset;

public record EncryptedSecret(byte[] ciphertext, String keyId) {
}
