package io.github.susongyan.redisops.platform.infrastructure.persistence;

import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.audit.AuditLog;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class MyBatisAuditRepository implements AuditRepository {
    private final AssetMapper mapper;
    private final ActorSnapshots actors;
    public MyBatisAuditRepository(AssetMapper mapper, ActorSnapshots actors) {
        this.mapper = mapper;
        this.actors = actors;
    }
    @Override
    public void append(String operator, String action, String resourceType, String resourceId, String result) {
        mapper.appendAudit(operator == null || operator.isBlank() ? "anonymous" : operator, action, resourceType,
                resourceId, result, actors.capture(operator));
    }
    @Override
    public void append(String operator, String action, String resourceType, String resourceId, String result,
            String detailsJson) {
        mapper.appendAuditDetails(operator == null || operator.isBlank() ? "anonymous" : operator, action,
                resourceType, resourceId, result, actors.capture(operator), detailsJson);
    }
    @Override
    public List<AuditLog> find(String operator, String resourceType, String resourceId, int limit) {
        return mapper.findAudits(operator, resourceType, resourceId, limit);
    }
}
