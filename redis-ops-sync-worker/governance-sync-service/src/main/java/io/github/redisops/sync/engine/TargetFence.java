package io.github.redisops.sync.engine;

import io.github.redisops.sync.protocol.RespProtocolException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public record TargetFence(String epoch, long generation, String runtimeId, String workerId, Instant publishedAt) {
    private static final String VERSION = "v1";

    public TargetFence {
        if (epoch == null || epoch.isBlank())
            throw new IllegalArgumentException("fence epoch is required");
        if (generation < 1)
            throw new IllegalArgumentException("fence generation must be positive");
        if (runtimeId == null || runtimeId.isBlank())
            throw new IllegalArgumentException("fence runtimeId is required");
        if (workerId == null || workerId.isBlank())
            throw new IllegalArgumentException("fence workerId is required");
        if (publishedAt == null)
            throw new IllegalArgumentException("fence publishedAt is required");
    }

    public byte[] encode() {
        return String.join("|", VERSION, text(epoch), Long.toString(generation), text(runtimeId), text(workerId),
                Long.toString(publishedAt.toEpochMilli())).getBytes(StandardCharsets.US_ASCII);
    }

    public static TargetFence decode(byte[] value) {
        try {
            String[] fields = new String(value, StandardCharsets.US_ASCII).split("\\|", -1);
            if (fields.length != 6 || !VERSION.equals(fields[0]))
                throw new RespProtocolException("unsupported target fence format");
            return new TargetFence(string(fields[1]), Long.parseLong(fields[2]), string(fields[3]),
                    string(fields[4]), Instant.ofEpochMilli(Long.parseLong(fields[5])));
        } catch (RespProtocolException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RespProtocolException("invalid target fence", error);
        }
    }

    public boolean ownedBy(TargetFence expected) {
        return epoch.equals(expected.epoch())
                && generation == expected.generation()
                && runtimeId.equals(expected.runtimeId())
                && workerId.equals(expected.workerId());
    }

    private static String text(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
