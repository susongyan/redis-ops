package io.github.redisops.sync.engine;

import io.github.redisops.sync.protocol.RespProtocolException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public record TargetCheckpoint(String epoch, long generation, String replicationId, long appliedOffset,
        int sourceDatabase, Instant updatedAt) {
    byte[] encode() {
        String repl = replicationId == null
                ? ""
                : Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(replicationId.getBytes(StandardCharsets.US_ASCII));
        return (epoch + "\t" + generation + "\t" + repl + "\t" + appliedOffset + "\t" + sourceDatabase
                + "\t" + updatedAt.toEpochMilli())
                .getBytes(StandardCharsets.US_ASCII);
    }

    static TargetCheckpoint decode(byte[] value) {
        try {
            String[] parts = new String(value, StandardCharsets.US_ASCII).split("\\t", -1);
            if (parts.length != 5 && parts.length != 6)
                throw new IllegalArgumentException();
            String replicationId = parts[2].isEmpty()
                    ? null
                    : new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.US_ASCII);
            int sourceDatabase = parts.length == 6 ? Integer.parseInt(parts[4]) : 0;
            int updatedAtIndex = parts.length == 6 ? 5 : 4;
            return new TargetCheckpoint(parts[0], Long.parseLong(parts[1]), replicationId,
                    Long.parseLong(parts[3]), sourceDatabase,
                    Instant.ofEpochMilli(Long.parseLong(parts[updatedAtIndex])));
        } catch (RuntimeException error) {
            throw new RespProtocolException("reserved checkpoint key contains invalid data", error);
        }
    }
}
