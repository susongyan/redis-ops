package io.github.redisops.platform.api.analysis;

import io.github.redisops.platform.api.ApiResponse;
import io.github.redisops.platform.application.analysis.AnalysisService;
import io.github.redisops.platform.domain.analysis.AnalysisEvidence;
import io.github.redisops.platform.domain.analysis.AnalysisRequest;
import io.github.redisops.platform.domain.analysis.AnalysisResult;
import io.github.redisops.platform.domain.analysis.AnalysisRun;
import io.github.redisops.platform.domain.analysis.AnalysisType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {
    private final AnalysisService service;
    private final io.github.redisops.platform.application.IdempotencyService idempotency;

    public AnalysisController(AnalysisService service,
            io.github.redisops.platform.application.IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping
    ApiResponse<AnalysisResult> analyze(
            @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Request body, HttpServletRequest httpRequest) {
        AnalysisRequest analysisRequest = new AnalysisRequest(body.type, body.resourceType, body.resourceId, body.facts,
                body.evidence, body.incidentRefs);
        String operator = httpRequest.getUserPrincipal().getName();
        AnalysisRun run = idempotency.execute(operator == null ? "anonymous" : operator, key, "ANALYSIS_CREATE",
                analysisRequest, () -> service.analyzeRun(analysisRequest), saved -> saved.id().toString(),
                id -> service.get(Long.parseLong(id)));
        return ApiResponse.of(run.result(),
                String.valueOf(httpRequest.getAttribute("requestId")));
    }

    @GetMapping
    ApiResponse<List<AnalysisRun>> list(@RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return ApiResponse.of(service.list(resourceType, resourceId, page, size),
                String.valueOf(request.getAttribute("requestId")));
    }

    @GetMapping("/{id}")
    ApiResponse<AnalysisRun> get(@org.springframework.web.bind.annotation.PathVariable long id,
            HttpServletRequest request) {
        return ApiResponse.of(service.get(id), String.valueOf(request.getAttribute("requestId")));
    }

    record Request(@NotNull AnalysisType type, String resourceType, String resourceId, Map<String, String> facts,
            List<AnalysisEvidence> evidence, List<String> incidentRefs) {
    }
}
