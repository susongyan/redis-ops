package io.github.redisops.application.audit;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.audit.AuditLog;
import io.github.redisops.domain.audit.AuditRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditService {
    private final AuditRepository audits;

    public AuditService(AuditRepository audits) {
        this.audits = audits;
    }

    public List<AuditLog> find(String operator, String resourceType, String resourceId, int limit) {
        if (limit < 1 || limit > 500)
            throw new BusinessException("INVALID_ARGUMENT", "limit must be between 1 and 500");
        return audits.find(normalize(operator), normalize(resourceType), normalize(resourceId), limit);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
