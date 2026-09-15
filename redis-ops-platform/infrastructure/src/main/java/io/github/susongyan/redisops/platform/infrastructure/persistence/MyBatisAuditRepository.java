package io.github.susongyan.redisops.platform.infrastructure.persistence;

import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.audit.AuditLog;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class MyBatisAuditRepository implements AuditRepository {
    private final AssetMapper mapper;
    public MyBatisAuditRepository(AssetMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    public void append(String operator, String action, String resourceType, String resourceId, String result) {
        mapper.appendAudit(operator == null || operator.isBlank() ? "anonymous" : operator, action, resourceType,
                resourceId, result);
    }
    @Override
    public List<AuditLog> find(String operator, String resourceType, String resourceId, int limit) {
        return mapper.findAudits(operator, resourceType, resourceId, limit);
    }
}
