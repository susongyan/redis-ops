package io.github.redisops.platform.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.platform.domain.analysis.*;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisAnalysisRunRepository implements AnalysisRunRepository {
    private final AnalysisRunMapper mapper;
    private final ObjectMapper json;

    public MyBatisAnalysisRunRepository(AnalysisRunMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public AnalysisRun save(AnalysisRun run) {
        AnalysisRunMapper.Row row = row(run);
        mapper.insert(row);
        return from(mapper.find(row.id));
    }

    @Override
    public List<AnalysisRun> find(String resourceType, String resourceId, int page, int size) {
        return mapper.findPage(resourceType, resourceId, (page - 1) * size, size).stream().map(this::from).toList();
    }

    @Override
    public Optional<AnalysisRun> findById(long id) {
        return Optional.ofNullable(mapper.find(id)).map(this::from);
    }

    private AnalysisRun from(AnalysisRunMapper.Row row) {
        try {
            AnalysisRequest request = new AnalysisRequest(AnalysisType.valueOf(row.analysisType), row.resourceType,
                    row.resourceId, java.util.Map.of(), json.readValue(row.evidenceJson,
                            new TypeReference<List<AnalysisEvidence>>() {
                            }),
                    List.of());
            AnalysisResult result = new AnalysisResult(row.requestId, AnalysisProtocol.valueOf(row.protocol),
                    row.provider,
                    row.status, row.summary, row.confidence, request.evidence(), json.readValue(row.recommendationsJson,
                            new TypeReference<List<AnalysisRecommendation>>() {
                            }),
                    row.createdAt);
            return new AnalysisRun(row.id, request, result);
        } catch (Exception e) {
            throw new IllegalStateException("analysis run data is invalid", e);
        }
    }

    private AnalysisRunMapper.Row row(AnalysisRun run) {
        try {
            AnalysisRunMapper.Row row = new AnalysisRunMapper.Row();
            row.requestId = run.result().requestId();
            row.analysisType = run.request().type().name();
            row.resourceType = run.request().resourceType();
            row.resourceId = run.request().resourceId();
            row.protocol = run.result().protocol().name();
            row.provider = run.result().provider();
            row.status = run.result().status();
            row.summary = run.result().summary();
            row.confidence = run.result().confidence();
            row.evidenceJson = json.writeValueAsString(run.result().evidence());
            row.recommendationsJson = json.writeValueAsString(run.result().recommendations());
            return row;
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize analysis result", e);
        }
    }
}
