package io.github.susongyan.redisops.platform.infrastructure.persistence;

import io.github.susongyan.redisops.platform.domain.operation.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisOperationRepository implements OperationRepository {
    private final OperationMapper mapper;
    private final ActorSnapshots actors;
    public MyBatisOperationRepository(OperationMapper mapper, ActorSnapshots actors) {
        this.mapper = mapper;
        this.actors = actors;
    }
    public List<OperationCommand> commands(boolean writes, boolean includeDisabled) {
        return mapper.commands(writes, includeDisabled);
    }
    public Optional<OperationCommand> command(String name) {
        return Optional.ofNullable(mapper.command(name)).filter(OperationCommand::enabled);
    }
    public RedisOperation save(RedisOperation x) {
        var r = row(x);
        r.operatorSnapshot = actors.capture(x.operatorName());
        mapper.insert(r);
        return mapper.find(r.id);
    }
    public Optional<RedisOperation> find(long id) {
        return Optional.ofNullable(mapper.find(id));
    }
    public List<RedisOperation> list(int page, int size) {
        return mapper.list(Math.max(0, page - 1) * size, size);
    }
    public boolean update(RedisOperation x, long version) {
        var r = row(x);
        r.version = version;
        if ("APPROVED".equals(x.status()))
            r.approverSnapshot = actors.capture(x.approverName());
        r.executorSnapshot = x.executorName() == null ? null : actors.capture(x.executorName());
        return mapper.update(r) == 1;
    }
    public boolean updateCommand(OperationCommand x, long version) {
        var r = commandRow(x, version);
        return mapper.updateCommand(r) == 1;
    }
    public OperationCommand createCommand(OperationCommand x) {
        var r = commandRow(x, 0);
        mapper.insertCommand(r);
        return mapper.commands(true, true).stream().filter(c -> c.id() == r.id).findFirst().orElseThrow();
    }
    private OperationMapper.CommandRow commandRow(OperationCommand x, long version) {
        var r = new OperationMapper.CommandRow();
        r.commandName = x.commandName();
        r.category = x.category();
        r.accessMode = x.accessMode();
        r.parameterSchemaJson = x.parameterSchemaJson();
        r.keyPosition = x.keyPosition();
        r.routingPolicy = x.routingPolicy();
        r.id = x.id() == null ? 0 : x.id();
        r.version = version;
        r.enabled = x.enabled();
        r.riskLevel = x.riskLevel();
        r.approvalPolicy = x.approvalPolicy();
        r.maxValueBytes = x.maxValueBytes();
        r.allowedDataTypesJson = x.allowedDataTypesJson();
        r.missingKeyPolicy = x.missingKeyPolicy();
        r.blockedByDefault = x.blockedByDefault();
        r.changeReason = x.changeReason();
        r.updatedBy = x.updatedBy();
        r.updatedBySnapshot = actors.capture(x.updatedBy());
        return r;
    }
    private static OperationMapper.OperationRow row(RedisOperation x) {
        var r = new OperationMapper.OperationRow();
        r.id = x.id();
        r.clusterId = x.clusterId();
        r.databaseNo = x.databaseNo();
        r.operationNo = x.operationNo();
        r.commandName = x.commandName();
        r.argumentsJson = x.argumentsJson();
        r.argumentsDigest = x.argumentsDigest();
        r.accessMode = x.accessMode();
        r.riskLevel = x.riskLevel();
        r.status = x.status();
        r.previewJson = x.previewJson();
        r.approvalNote = x.approvalNote();
        r.operatorName = x.operatorName();
        r.approverName = x.approverName();
        r.resultJson = x.resultJson();
        r.approverSnapshot = x.approverSnapshot();
        r.executorName = x.executorName();
        r.executorSnapshot = x.executorSnapshot();
        return r;
    }
}
