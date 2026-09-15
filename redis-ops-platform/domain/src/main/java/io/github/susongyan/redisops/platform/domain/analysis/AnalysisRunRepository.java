package io.github.susongyan.redisops.platform.domain.analysis;

import java.util.List;
import java.util.Optional;

public interface AnalysisRunRepository {
    AnalysisRun save(AnalysisRun run);

    List<AnalysisRun> find(String resourceType, String resourceId, int page, int size);

    Optional<AnalysisRun> findById(long id);
}
