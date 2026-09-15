package io.github.susongyan.redisops.platform.domain.asset;

public record EncryptedSecret(byte[] ciphertext, String keyId) {
}
