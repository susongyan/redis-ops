package io.github.susongyan.redisops.platform.domain.analysis;

import java.util.List;
import java.util.Optional;

public interface AnalysisAgentProfileRepository {
    AnalysisAgentProfile save(AnalysisAgentProfile profile);

    List<AnalysisAgentProfile> findAll(boolean includeDisabled);

    Optional<AnalysisAgentProfile> findById(long id);

    boolean update(AnalysisAgentProfile profile, long version);
}
