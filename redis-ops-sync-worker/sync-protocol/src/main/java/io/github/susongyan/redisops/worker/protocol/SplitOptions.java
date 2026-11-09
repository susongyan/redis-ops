package io.github.susongyan.redisops.worker.protocol;

/**
 * Threshold routing for split-mode RDB parsing ({@code RdbStreamParser.parse(consumer, options)}).
 * Enabled only when the runner allows element-level splitting: the RESTORE threshold is positive
 * and the task command policy permits safe split. The single-argument {@code parse} never sees
 * this class and keeps its exact legacy behavior.
 *
 * <p>
 * Both thresholds compare against the value's wire (DUMP) size — for LZF-compressed blobs
 * that is the compressed size, which is what a RESTORE would send.
 *
 * @param maxRestoreBytes
 *            values at or below this size keep using the RESTORE path
 * @param hardCapBytes
 *            total fallback cap; larger values are skipped with an alert
 * @param skipUnsupportedTypes
 *            when {@code true}, MODULE_2 values are skipped instead of failing
 *            the task (old MODULE values always fail: no length framing)
 */
public record SplitOptions(long maxRestoreBytes, long hardCapBytes, boolean skipUnsupportedTypes) {
    public SplitOptions {
        if (maxRestoreBytes <= 0)
            throw new IllegalArgumentException("maxRestoreBytes must be positive");
        if (hardCapBytes < maxRestoreBytes)
            throw new IllegalArgumentException("hardCapBytes must be >= maxRestoreBytes");
    }
}
