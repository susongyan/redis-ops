package io.github.redisops.domain.audit;

import java.util.List;

public interface AuditRepository {
    void append(String operator, String action, String resourceType, String resourceId, String result);
    List<AuditLog> find(String operator, String resourceType, String resourceId, int limit);
}
