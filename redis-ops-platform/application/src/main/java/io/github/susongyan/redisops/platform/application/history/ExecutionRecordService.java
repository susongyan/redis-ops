package io.github.susongyan.redisops.platform.application.history;

import io.github.susongyan.redisops.platform.domain.history.ExecutionRecordRepository;
import io.github.susongyan.redisops.platform.domain.history.ExecutionRecordRepository.Type;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.application.audit.AuditDetails;
import io.github.susongyan.redisops.platform.common.BusinessException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionRecordService {
    private final ExecutionRecordRepository records;
    private final AuditRepository audits;
    public ExecutionRecordService(ExecutionRecordRepository records, AuditRepository audits) {
        this.records = records;
        this.audits = audits;
    }
    @Transactional
    public void delete(Type type, long id, long version, String operator) {
        var snapshot = records.lock(type, id);
        if (snapshot.version() != version)
            throw new BusinessException("CONCURRENT_MODIFICATION", "记录已变更，请刷新后重试");
        if (!type.canDelete(snapshot.status()))
            throw new BusinessException("RECORD_NOT_FINISHED", "只能删除已结束的记录");
        if (snapshot.linked())
            throw new BusinessException("RECORD_REFERENCED", "关联同步任务的校验记录需要保留");
        if (snapshot.executing())
            throw new BusinessException("RECORD_EXECUTING", "后台执行尚未结束，请稍后重试");
        if (!records.delete(type, id, version))
            throw new BusinessException("CONCURRENT_MODIFICATION", "记录已变更，请刷新后重试");
        audits.append(operator, "EXECUTION_RECORD_DELETE", type.name(), Long.toString(id), "SUCCESS",
                AuditDetails.change("删除只读执行记录", AuditDetails.fields("类型", type, "记录 ID", id,
                        "状态", snapshot.status(), "版本", version), Map.of(), null));
    }
}
