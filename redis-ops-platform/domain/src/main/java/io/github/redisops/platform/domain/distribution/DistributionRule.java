package io.github.redisops.platform.domain.distribution;

import java.nio.charset.StandardCharsets;

public record DistributionRule(String id, String name, String prefix, Kind kind, String delimiter, int segments) {
    public enum Kind {
        FIXED, SEGMENTS
    }
    public DistributionRule {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || kind == null)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_RULE");
        if (name == null || name.isBlank() || bytes(name) > 256)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_RULE_NAME");
        prefix = prefix == null ? "" : prefix;
        if (bytes(prefix) > 256)
            throw new IllegalArgumentException("DISTRIBUTION_PREFIX_TOO_LONG");
        if (kind == Kind.SEGMENTS
                && (delimiter == null || bytes(delimiter) < 1 || bytes(delimiter) > 8 || segments < 1 || segments > 8))
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_SEGMENTS");
    }
    public static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
