package io.github.redisops.domain.asset;

public record ManagedApplication(Long id, String code, String name, String owner,
                                 String businessLine, String status, long version) { }
