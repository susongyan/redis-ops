package io.github.susongyan.redisops.platform.application.analysis;

import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisAgentProfile;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisAgentProfileRepository;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisProtocol;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisAgentProfileService {
    private final AnalysisAgentProfileRepository profiles;
    private final AuditRepository audits;

    public AnalysisAgentProfileService(AnalysisAgentProfileRepository profiles, AuditRepository audits) {
        this.profiles = profiles;
        this.audits = audits;
    }

    @Transactional
    public AnalysisAgentProfile create(String name, AnalysisProtocol protocol, String endpoint, boolean enabled,
            String supportedTypesJson, int timeoutMs, int priority, String operator) {
        if (endpoint != null && endpoint.length() > 512)
            throw new BusinessException("INVALID_ARGUMENT", "endpoint is too long");
        AnalysisAgentProfile result = profiles.save(
                new AnalysisAgentProfile(null, name, protocol, endpoint, enabled, normalizeTypes(supportedTypesJson),
                        timeoutMs, priority, 0, Instant.now(), Instant.now()));
        audits.append(operator, "ANALYSIS_AGENT_CREATE", "ANALYSIS_AGENT", Long.toString(result.id()), "SUCCESS");
        return result;
    }

    public List<AnalysisAgentProfile> list(boolean includeDisabled) {
        return profiles.findAll(includeDisabled);
    }

    @Transactional
    public AnalysisAgentProfile update(long id, long version, String name, AnalysisProtocol protocol, String endpoint,
            boolean enabled, String supportedTypesJson, int timeoutMs, int priority, String operator) {
        AnalysisAgentProfile current = profiles.findById(id)
                .orElseThrow(() -> BusinessException.notFound("analysisAgent", id));
        AnalysisAgentProfile next = new AnalysisAgentProfile(id, name, protocol, endpoint, enabled,
                normalizeTypes(supportedTypesJson), timeoutMs, priority, version, current.createdAt(), Instant.now());
        if (!profiles.update(next, version))
            throw new BusinessException("VERSION_CONFLICT", "analysis agent changed");
        AnalysisAgentProfile result = profiles.findById(id)
                .orElseThrow(() -> BusinessException.notFound("analysisAgent", id));
        audits.append(operator, "ANALYSIS_AGENT_UPDATE", "ANALYSIS_AGENT", Long.toString(id), "SUCCESS");
        return result;
    }

    private static String normalizeTypes(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }
}
