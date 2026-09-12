package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.operation.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisOperationRepository implements OperationRepository {
    private final OperationMapper mapper;
    public MyBatisOperationRepository(OperationMapper mapper) {
        this.mapper = mapper;
    }
    public List<OperationCommand> commands(boolean writes, boolean includeDisabled) {
        return mapper.commands(writes, includeDisabled);
    }
    public Optional<OperationCommand> command(String name) {
        return Optional.ofNullable(mapper.command(name));
    }
    public RedisOperation save(RedisOperation x) {
        var r = row(x);
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
        return mapper.update(r) == 1;
    }
    public boolean updateCommand(OperationCommand x, long version) {
        var r = new OperationMapper.CommandRow();
        r.id = x.id();
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
        return mapper.updateCommand(r) == 1;
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
        return r;
    }
}
