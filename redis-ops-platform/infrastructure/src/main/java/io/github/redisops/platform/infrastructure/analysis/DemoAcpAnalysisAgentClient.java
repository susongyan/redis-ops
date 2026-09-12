package io.github.redisops.platform.infrastructure.analysis;

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
import org.springframework.core.annotation.Order;

/** Development-only ACP result simulator. It never connects to Redis or an external agent. */
@Order(15)
public class DemoAcpAnalysisAgentClient implements AnalysisAgentClient {
    private static final Set<AnalysisType> TYPES = Set.copyOf(EnumSet.allOf(AnalysisType.class));

    @Override
    public String provider() {
        return "demo-acp-agent";
    }

    @Override
    public AnalysisProtocol protocol() {
        return AnalysisProtocol.ACP;
    }

    @Override
    public Set<AnalysisType> supportedTypes() {
        return TYPES;
    }

    @Override
    public AnalysisResult analyze(String requestId, AnalysisRequest request) {
        String status = request.facts().getOrDefault("status", "UNKNOWN");
        String summary = "ACP 模拟 Agent 判断：" + request.type() + " 场景当前为 " + status
                + "，建议先完成证据核对，再执行受控操作。";
        List<AnalysisEvidence> evidence = List.of(
                new AnalysisEvidence("acp-demo:request", "ACP_SIMULATION", "模拟 Agent 已接收结构化上下文"),
                new AnalysisEvidence("acp-demo:policy", "ACP_SIMULATION", "模拟 Agent 返回人工确认优先策略"));
        List<AnalysisRecommendation> recommendations = List.of(
                new AnalysisRecommendation("CONFIRM_CONTEXT", "核对当前状态、时间窗口和关联指标", true),
                new AnalysisRecommendation("EXECUTE_CONTROLLED_ACTION", "确认影响范围后，通过现有预检查和审批流程执行", true));
        return new AnalysisResult(requestId, protocol(), provider(), "COMPLETED", summary, 0.86d, evidence,
                recommendations, Instant.now());
    }
}
