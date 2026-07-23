package io.github.redisops.api.audit;

import io.github.redisops.api.ApiResponse;
import io.github.redisops.api.RequestIdFilter;
import io.github.redisops.application.audit.AuditService;
import io.github.redisops.domain.audit.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audits")
public class AuditController {
    private final AuditService audits;

    public AuditController(AuditService audits) {
        this.audits = audits;
    }

    @GetMapping
    public ApiResponse<List<AuditLog>> find(
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        return ApiResponse.of(audits.find(operator, resourceType, resourceId, limit),
                String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
