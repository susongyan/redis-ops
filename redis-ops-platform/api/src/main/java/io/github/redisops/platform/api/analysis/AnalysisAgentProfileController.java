package io.github.redisops.platform.api.analysis;

import io.github.redisops.platform.api.ApiResponse;
import io.github.redisops.platform.api.RequestIdFilter;
import io.github.redisops.platform.application.IdempotencyService;
import io.github.redisops.platform.application.analysis.AnalysisAgentProfileService;
import io.github.redisops.platform.domain.analysis.AnalysisAgentProfile;
import io.github.redisops.platform.domain.analysis.AnalysisProtocol;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analysis-agents")
public class AnalysisAgentProfileController {
    private final AnalysisAgentProfileService service;
    private final IdempotencyService idempotency;

    public AnalysisAgentProfileController(AnalysisAgentProfileService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping
    ApiResponse<List<AnalysisAgentProfile>> list(@RequestParam(defaultValue = "false") boolean includeDisabled,
            HttpServletRequest request) {
        return response(service.list(includeDisabled), request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AnalysisAgentProfile> create(@RequestHeader("Idempotency-Key") String key, @RequestBody Command body,
            HttpServletRequest request) {
        String operator = operator(request);
        AnalysisAgentProfile result = idempotency.execute(operator, key, "ANALYSIS_AGENT_CREATE", body,
                () -> service.create(body.name, body.protocol, body.endpoint, body.enabled, body.supportedTypesJson,
                        body.timeoutMs, body.priority, operator),
                x -> x.id().toString(), x -> service.list(true).stream()
                        .filter(item -> item.id().equals(Long.valueOf(x))).findFirst().orElseThrow());
        return response(result, request);
    }

    @PutMapping("/{id}")
    ApiResponse<AnalysisAgentProfile> update(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") long version, @RequestBody Command body, HttpServletRequest request) {
        String operator = operator(request);
        AnalysisAgentProfile result = idempotency.execute(operator, key, "ANALYSIS_AGENT_UPDATE", body,
                () -> service.update(id, version, body.name, body.protocol, body.endpoint, body.enabled,
                        body.supportedTypesJson, body.timeoutMs, body.priority, operator),
                x -> x.id().toString(), x -> service.list(true).stream()
                        .filter(item -> item.id().equals(Long.valueOf(x))).findFirst().orElseThrow());
        return response(result, request);
    }

    record Command(@NotBlank String name, AnalysisProtocol protocol, String endpoint, boolean enabled,
            String supportedTypesJson, int timeoutMs, int priority) {
    }

    private static String operator(HttpServletRequest request) {
        String value = request.getHeader("X-Operator");
        return value == null ? "anonymous" : value;
    }

    private static <T> ApiResponse<T> response(T value, HttpServletRequest request) {
        return ApiResponse.of(value, String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
