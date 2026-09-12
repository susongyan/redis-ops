package io.github.redisops.platform.application.analysis;

import io.github.redisops.platform.domain.analysis.AnalysisAgentClient;
import io.github.redisops.platform.domain.analysis.AnalysisEvidence;
import io.github.redisops.platform.domain.analysis.AnalysisProtocol;
import io.github.redisops.platform.domain.analysis.AnalysisRecommendation;
import io.github.redisops.platform.domain.analysis.AnalysisRequest;
import io.github.redisops.platform.domain.analysis.AnalysisResult;
import io.github.redisops.platform.domain.analysis.AnalysisType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
@Order(100)
public class RuleBasedAnalysisClient implements AnalysisAgentClient {
    private static final Set<AnalysisType> TYPES = EnumSet.allOf(AnalysisType.class);

    @Override
    public String provider() {
        return "rule-based";
    }

    @Override
    public AnalysisProtocol protocol() {
        return AnalysisProtocol.RULE;
    }

    @Override
    public Set<AnalysisType> supportedTypes() {
        return TYPES;
    }

    @Override
    public AnalysisResult analyze(String requestId, AnalysisRequest request) {
        String summary = summary(request);
        List<AnalysisEvidence> evidence = request.evidence();
        List<AnalysisRecommendation> recommendations = recommendations(request);
        return new AnalysisResult(requestId, protocol(), provider(), "COMPLETED", summary,
                evidence.isEmpty() ? 0.35d : 0.60d, evidence, recommendations,
                Instant.now());
    }

    private String summary(AnalysisRequest request) {
        if (request.facts().isEmpty())
            return "当前没有足够的结构化事实，无法给出明确判断。请补充指标、状态或事件证据后重试。";
        return switch (request.type()) {
            case ALERT -> String.format("告警%s：级别 %s，当前状态 %s。建议先确认指标趋势和关联事件，再决定是否处理。",
                    fact(request, "title", "事件"), fact(request, "severity", "未标记"),
                    fact(request, "status", "未知"));
            case SYNC -> String.format("同步任务当前为 %s，RPO 延迟 %s 秒，offset gap %s。先确认 Worker、租约和源端写入状态。",
                    fact(request, "status", "未知"), fact(request, "rpoSeconds", "未知"),
                    fact(request, "offsetGapBytes", "未知"));
            case RISK_SCAN -> String.format("风险扫描%s：已扫描 %s / %s 个 Key，发现 %s 项；大 Key %s 个、无 TTL %s 个。",
                    statusSuffix(request), fact(request, "scannedKeys", "0"), fact(request, "plannedKeys", "未知"),
                    fact(request, "findingCount", "0"), fact(request, "largeKeyCount", "0"),
                    fact(request, "noTtlCount", "0"));
            case VALIDATION -> String.format("数据校验当前为 %s：已比较 %s / %s 个 Key，发现 %s 项差异，降级 %s 项，未确定 %s 项。",
                    fact(request, "status", "未知"), fact(request, "comparedKeys", "0"),
                    fact(request, "plannedKeys", "未知"), fact(request, "differenceCount", "0"),
                    fact(request, "degradedCount", "0"), fact(request, "inconclusiveCount", "0"));
            case INCIDENT -> "已根据当前事件事实生成初步判断，仍需结合时间线和历史故障记录复核。";
        };
    }

    private List<AnalysisRecommendation> recommendations(AnalysisRequest request) {
        return switch (request.type()) {
            case ALERT -> List.of(new AnalysisRecommendation("CHECK_ALERT_TREND", "核对最近采集指标、持续时间和同资源的关联告警", true));
            case SYNC ->
                List.of(new AnalysisRecommendation("CHECK_SYNC_HEALTH", "确认 Worker 租约、同步通道、RPO 和源端写隔离状态", true));
            case RISK_SCAN ->
                List.of(new AnalysisRecommendation("REVIEW_RISK_FINDINGS", "优先查看大 Key 和无 TTL 项，确认业务影响后再制定治理动作", true));
            case VALIDATION -> List.of(
                    new AnalysisRecommendation("REVIEW_VALIDATION_DIFFS", "先区分单边缺失、类型差异、TTL 差异和未确定项，不要直接自动修复", true));
            case INCIDENT -> List.of(new AnalysisRecommendation("REVIEW_EVIDENCE", "确认指标、告警和历史案例后再决定生产动作", true));
        };
    }

    private String fact(AnalysisRequest request, String name, String fallback) {
        String value = request.facts().get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String statusSuffix(AnalysisRequest request) {
        return "（" + fact(request, "status", "未知") + "）";
    }
}
