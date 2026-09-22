package io.github.susongyan.redisops.platform.application.governance;

import io.github.susongyan.redisops.platform.application.audit.AuditDetails;
import io.github.susongyan.redisops.platform.domain.governance.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class GovernanceAudit {
    private GovernanceAudit() {
    }
    static String details(TtlGovernanceTask t) {
        return AuditDetails.change("TTL 治理操作", Map.of(), AuditDetails.fields(
                "任务", t.taskNo(), "状态", t.status(), "审批状态", t.approvalStatus(), "版本", t.version(),
                "集群 ID", t.clusterId(), "DB", t.databaseNo(), "规则 SHA-256", fingerprint(t.includePattern()),
                "目标 TTL 秒", t.targetTtlSeconds(), "匹配检查上限", t.maxKeys(), "速率 Key/s", t.scanRatePerSecond()), null);
    }
    static String details(CleanupGovernanceTask t) {
        return AuditDetails.change("数据清理操作", Map.of(), AuditDetails.fields(
                "任务", t.taskNo(), "状态", t.status(), "审批状态", t.approvalStatus(), "版本", t.version(),
                "集群 ID", t.clusterId(), "DB", t.databaseNo(), "规则 SHA-256", fingerprint(t.includePattern()),
                "影响上限", t.impactLimit(), "速率 Key/s", t.scanRatePerSecond()), t.approvalNote());
    }
    static String skip(TtlGovernanceStatus before, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("GOVERNANCE_SKIP_REASON_REQUIRED");
        return AuditDetails.change("明确跳过预检并授权执行", Map.of("状态", before),
                Map.of("状态", TtlGovernanceStatus.RUNNING), reason.trim());
    }
    private static String fingerprint(String pattern) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(pattern.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE");
        }
    }
}
