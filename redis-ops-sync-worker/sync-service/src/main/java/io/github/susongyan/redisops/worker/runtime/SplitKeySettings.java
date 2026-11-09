package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.SplitOptions;

/**
 * Element-level split settings for the full RDB phase (design doc §4). {@code maxRestoreBytes == 0}
 * disables splitting entirely: the parser keeps its legacy single-argument behavior and every key
 * flows through the RESTORE pool as before. {@code hardCapBytes} must stay below the target's
 * {@code proto-max-bulk-len} (default 512MB) — that invariant is what keeps a fallback RESTORE
 * itself sendable; the writer additionally probes the live value per element as a defensive check.
 *
 * @param maxRestoreBytes
 *            single-key wire size at or below which the RESTORE path is used
 * @param splitBatchBytes
 *            byte cap of one element batch (commands plus arguments)
 * @param splitBatchCommands
 *            command cap of one element batch
 * @param hardCapBytes
 *            total cap for split or fallback RESTORE; larger values are skipped
 * @param skipUnsupportedTypes
 *            whether parse-level unsupported types (MODULE_2) skip instead of failing
 */
record SplitKeySettings(long maxRestoreBytes, long splitBatchBytes, int splitBatchCommands,
        long hardCapBytes, boolean skipUnsupportedTypes) {

    SplitKeySettings {
        if (maxRestoreBytes < 0)
            throw new IllegalArgumentException("maxRestoreBytes cannot be negative");
        if (hardCapBytes < maxRestoreBytes)
            throw new IllegalArgumentException("hardCapBytes must be >= maxRestoreBytes");
        if (maxRestoreBytes > 0 && splitBatchBytes < 1024)
            throw new IllegalArgumentException("splitBatchBytes must be at least 1024 when splitting is enabled");
        if (maxRestoreBytes > 0 && (splitBatchCommands < 1 || splitBatchCommands > 10_000))
            throw new IllegalArgumentException("splitBatchCommands must be between 1 and 10000");
    }

    boolean enabled() {
        return maxRestoreBytes > 0;
    }

    SplitOptions toParserOptions() {
        return new SplitOptions(maxRestoreBytes, hardCapBytes, skipUnsupportedTypes);
    }
}
