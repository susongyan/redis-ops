package io.github.susongyan.redisops.platform.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.domain.governance.TtlGovernanceStatus;

record GovernanceJobCommand(long taskId, long version, boolean apply, boolean resume) {
    static GovernanceJobCommand parse(String payload) {
        try {
            var body = new ObjectMapper().readTree(payload);
            String action = body.path("action").asText();
            if (!body.path("taskId").canConvertToLong() || !body.path("version").canConvertToLong()
                    || !("APPLY".equals(action) || "DRY_RUN".equals(action)))
                throw new IllegalArgumentException("GOVERNANCE_JOB_INVALID");
            return new GovernanceJobCommand(body.path("taskId").asLong(), body.path("version").asLong(),
                    "APPLY".equals(action), body.path("resume").asBoolean(false));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("GOVERNANCE_JOB_INVALID");
        }
    }
    TtlGovernanceStatus phase() {
        return apply ? TtlGovernanceStatus.RUNNING : TtlGovernanceStatus.DRY_RUN;
    }
    boolean accepts(long currentVersion, TtlGovernanceStatus currentStatus) {
        return version == currentVersion && currentStatus == phase();
    }
}
