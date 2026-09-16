package io.github.susongyan.redisops.worker.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/** Static deployment compatibility information; never includes task data or credentials. */
@Component
public final class SyncCapabilitiesInfoContributor implements InfoContributor {
    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("syncCapabilities", Map.of(
                "commandPolicyVersions", List.of("v1", "v2"),
                "batchConfirmationVersion", 2,
                "policyAwareClaims", true,
                "sourceTransactions", false));
    }
}
