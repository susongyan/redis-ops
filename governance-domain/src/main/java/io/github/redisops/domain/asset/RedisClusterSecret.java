package io.github.redisops.domain.asset;

import java.util.UUID;

public record RedisClusterSecret(long clusterId, UUID secretUuid, byte[] encryptedSecret,
        String keyId, String username, SecretStatus secretStatus,
        long version) {
    public boolean passwordConfigured() {
        return secretStatus == SecretStatus.ENCRYPTED;
    }

    public String authType() {
        return username == null || username.isBlank() ? "PASSWORD" : "ACL";
    }
}
