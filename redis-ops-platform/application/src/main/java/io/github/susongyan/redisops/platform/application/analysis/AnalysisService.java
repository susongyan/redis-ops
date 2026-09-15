package io.github.susongyan.redisops.platform.application.analysis;

import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisAgentClient;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisRequest;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisResult;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisRun;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisRunRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service
public class AnalysisService {
    private final List<AnalysisAgentClient> clients;
    private final AnalysisRunRepository runs;

    public AnalysisService(List<AnalysisAgentClient> clients, AnalysisRunRepository runs) {
        this.clients = clients == null
                ? List.of()
                : clients.stream().sorted((left, right) -> Integer.compare(order(left), order(right))).toList();
        this.runs = runs;
    }

    public AnalysisResult analyze(AnalysisRequest request) {
        return analyzeRun(request).result();
    }

    public AnalysisRun analyzeRun(AnalysisRequest request) {
        String requestId = UUID.randomUUID().toString();
        for (AnalysisAgentClient client : clients) {
            if (!client.supportedTypes().contains(request.type()))
                continue;
            AnalysisResult result;
            try {
                result = client.analyze(requestId, request);
            } catch (RuntimeException failed) {
                // Do not hide external failures behind a successful rule-based result.
                throw new BusinessException("ANALYSIS_PROVIDER_FAILED", "analysis provider request failed");
            }
            // Persistence failure is not a provider failure: never repeat the analysis or hide it.
            return runs.save(new AnalysisRun(null, request, result));
        }
        throw new BusinessException("ANALYSIS_PROVIDER_UNAVAILABLE", "no analysis provider supports " + request.type());
    }

    public List<AnalysisRun> list(String resourceType, String resourceId, int page, int size) {
        if (page < 1 || size < 1 || size > 100)
            throw new BusinessException("INVALID_ARGUMENT", "page/size is invalid");
        return runs.find(resourceType, resourceId, page, size);
    }

    public AnalysisRun get(long id) {
        return runs.findById(id).orElseThrow(() -> BusinessException.notFound("analysisRun", id));
    }

    private static int order(AnalysisAgentClient client) {
        Order annotation = client.getClass().getAnnotation(Order.class);
        return annotation == null ? 100 : annotation.value();
    }
}
