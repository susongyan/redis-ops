package io.github.susongyan.redisops.platform.domain.analysis;

import java.util.Set;

public interface AnalysisAgentClient {
    String provider();

    AnalysisProtocol protocol();

    Set<AnalysisType> supportedTypes();

    AnalysisResult analyze(String requestId, AnalysisRequest request);
}
