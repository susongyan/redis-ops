package io.github.susongyan.redisops.worker.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyncCapabilitiesInfoContributorTest {
    @Test
    void declaresOnlyImplementedCapabilities() {
        var builder = new Info.Builder();
        new SyncCapabilitiesInfoContributor().contribute(builder);
        Map<?, ?> capabilities = (Map<?, ?>) builder.build().getDetails().get("syncCapabilities");
        assertEquals(List.of("v1", "v2"), capabilities.get("commandPolicyVersions"));
        assertEquals(2, capabilities.get("batchConfirmationVersion"));
        assertEquals(true, capabilities.get("policyAwareClaims"));
        assertEquals(false, capabilities.get("sourceTransactions"));
    }
}
