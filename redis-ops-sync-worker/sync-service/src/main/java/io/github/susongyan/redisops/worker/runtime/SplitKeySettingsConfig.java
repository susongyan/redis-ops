package io.github.susongyan.redisops.worker.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single construction point of {@link SplitKeySettings} plus the precheck sampling budget, so the
 * {@code sync.engine.full-apply-split-*} defaults live in one Java location instead of one copy per
 * runner factory and the precheck executor. Zero disables splitting entirely; a non-positive split
 * batch size inherits the shared transaction byte cap (design doc §4).
 */
@Component
class SplitKeySettingsConfig {
    private final SplitKeySettings settings;
    private final int precheckSampleKeys;

    SplitKeySettingsConfig(
            @Value("${sync.engine.full-apply-max-restore-bytes:67108864}") long maxRestoreBytes,
            @Value("${sync.engine.full-apply-split-batch-bytes:0}") long splitBatchBytes,
            @Value("${sync.engine.full-apply-split-batch-commands:512}") int splitBatchCommands,
            @Value("${sync.engine.full-apply-transaction-max-bytes:4194304}") long fullApplyTransactionMaxBytes,
            @Value("${sync.engine.full-apply-split-hard-cap-bytes:503316480}") long splitHardCapBytes,
            @Value("${sync.engine.full-apply-unsupported-type-policy:FAIL}") String unsupportedTypePolicy,
            @Value("${sync.engine.full-apply-precheck-sample-keys:5000}") int precheckSampleKeys) {
        if (precheckSampleKeys < 0)
            throw new IllegalArgumentException("full apply precheck sample keys cannot be negative (0 disables)");
        this.settings = new SplitKeySettings(maxRestoreBytes,
                splitBatchBytes > 0 ? splitBatchBytes : fullApplyTransactionMaxBytes,
                splitBatchCommands, splitHardCapBytes, "SKIP".equalsIgnoreCase(unsupportedTypePolicy));
        this.precheckSampleKeys = precheckSampleKeys;
    }

    SplitKeySettings settings() {
        return settings;
    }

    /**
     * Per-node SCAN key budget of the precheck source sampler (design doc §5.4); 0 disables the
     * sampling check but never the {@code proto-max-bulk-len} validation.
     */
    int precheckSampleKeys() {
        return precheckSampleKeys;
    }
}
