package io.github.susongyan.redisops.worker.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Replaces the success checkpoint while effects are unconfirmed. Contains no command data. */
final class PendingTargetBatch {
    private static final String PREFIX = "pending-batch-v2:";

    private PendingTargetBatch() {
    }

    static byte[] encode(TargetCheckpoint previous, TargetCheckpoint next) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return (PREFIX + UUID.randomUUID() + ":" +
                (previous == null ? "" : encoder.encodeToString(previous.encode())) + ":" +
                encoder.encodeToString(next.encode())).getBytes(StandardCharsets.US_ASCII);
    }

    static boolean isPending(byte[] value) {
        return new String(value, StandardCharsets.US_ASCII).startsWith(PREFIX);
    }

    static SyncBlockedException unresolved() {
        return new SyncBlockedException("BLOCKED_TARGET_BATCH_UNCONFIRMED",
                "target batch outcome requires reconciliation; automatic replay is prohibited");
    }
}
