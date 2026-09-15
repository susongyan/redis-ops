package io.github.susongyan.redisops.platform.infrastructure.persistence;

import io.github.susongyan.redisops.platform.domain.analysis.AnalysisAgentProfile;
import io.github.susongyan.redisops.platform.domain.analysis.AnalysisAgentProfileRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisAnalysisAgentProfileRepository implements AnalysisAgentProfileRepository {
    private final AnalysisAgentMapper mapper;

    public MyBatisAnalysisAgentProfileRepository(AnalysisAgentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AnalysisAgentProfile save(AnalysisAgentProfile profile) {
        AnalysisAgentMapper.Row row = row(profile);
        mapper.insert(row);
        return mapper.find(row.id);
    }

    @Override
    public List<AnalysisAgentProfile> findAll(boolean includeDisabled) {
        return mapper.findAll(includeDisabled);
    }

    @Override
    public Optional<AnalysisAgentProfile> findById(long id) {
        return Optional.ofNullable(mapper.find(id));
    }

    @Override
    public boolean update(AnalysisAgentProfile profile, long version) {
        AnalysisAgentMapper.Row row = row(profile);
        row.version = version;
        return mapper.update(row) == 1;
    }

    private static AnalysisAgentMapper.Row row(AnalysisAgentProfile profile) {
        AnalysisAgentMapper.Row row = new AnalysisAgentMapper.Row();
        row.id = profile.id();
        row.name = profile.name();
        row.protocol = profile.protocol().name();
        row.endpoint = profile.endpoint();
        row.enabled = profile.enabled();
        row.supportedTypesJson = profile.supportedTypesJson();
        row.timeoutMs = profile.timeoutMs();
        row.priority = profile.priority();
        return row;
    }
}
