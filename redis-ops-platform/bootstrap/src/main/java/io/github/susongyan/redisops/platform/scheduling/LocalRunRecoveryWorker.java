package io.github.susongyan.redisops.platform.scheduling;

import io.github.susongyan.redisops.platform.application.asset.AssetService;
import io.github.susongyan.redisops.platform.application.governance.CleanupGovernanceService;
import io.github.susongyan.redisops.platform.application.governance.TtlGovernanceService;
import io.github.susongyan.redisops.platform.application.risk.RiskScanService;
import io.github.susongyan.redisops.platform.application.validation.ValidationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails runs left behind by dead instances for the request-local executed job kinds (validation,
 * risk scan, discovery, ttl/cleanup governance). They have no async-job lease anymore, so a stale
 * run heartbeat is the only crash signal; it must be checked periodically, not only at startup,
 * because another instance of this worker shares the database and its live runs must be left alone.
 */
@Component
@ConditionalOnProperty(name = "platform.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class LocalRunRecoveryWorker {
    private final ValidationService validations;
    private final RiskScanService scans;
    private final AssetService assets;
    private final TtlGovernanceService ttlGovernance;
    private final CleanupGovernanceService cleanupGovernance;

    public LocalRunRecoveryWorker(ValidationService validations, RiskScanService scans, AssetService assets,
            TtlGovernanceService ttlGovernance, CleanupGovernanceService cleanupGovernance) {
        this.validations = validations;
        this.scans = scans;
        this.assets = assets;
        this.ttlGovernance = ttlGovernance;
        this.cleanupGovernance = cleanupGovernance;
    }

    @Scheduled(initialDelayString = "${platform.jobs.recovery.initial-delay-ms:15000}", fixedDelayString = "${platform.jobs.recovery.interval-ms:300000}")
    public void recover() {
        validations.recoverInterrupted();
        scans.recoverInterrupted();
        assets.recoverInterrupted();
        ttlGovernance.recoverInterrupted();
        cleanupGovernance.recoverInterrupted();
    }
}
