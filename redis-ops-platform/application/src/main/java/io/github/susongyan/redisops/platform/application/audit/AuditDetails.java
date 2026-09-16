package io.github.susongyan.redisops.platform.application.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.domain.asset.RedisCluster;
import io.github.susongyan.redisops.platform.domain.asset.ManagedApplication;
import io.github.susongyan.redisops.platform.domain.operation.OperationCommand;
import io.github.susongyan.redisops.platform.domain.sync.SyncTask;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit field projections only. Never pass request bodies or credential objects. */
public final class AuditDetails {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AuditDetails() {
    }
    public static Map<String, Object> fields(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2)
            result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    public static String change(String summary, Map<String, Object> before, Map<String, Object> after, String reason) {
        var changes = new ArrayList<Map<String, Object>>();
        var keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (!Objects.equals(before.get(key), after.get(key)))
                changes.add(fields("field", key, "before", display(before.get(key)), "after", display(after.get(key))));
        }
        try {
            String value = JSON.writeValueAsString(
                    fields("summary", display(summary), "changes", changes, "reason", display(reason)));
            if (value.getBytes(StandardCharsets.UTF_8).length > 32768)
                throw new IllegalArgumentException("AUDIT_DETAILS_TOO_LARGE");
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("AUDIT_DETAILS_INVALID");
        }
    }
    private static Object display(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean)
            return value;
        String text = value.toString();
        return text.length() <= 512 ? text : text.substring(0, 512) + "…（已省略）";
    }
    public static Map<String, Object> cluster(RedisCluster c) {
        if (c == null)
            return Map.of();
        // Endpoint can carry connection information; record only its changed flag at the call site.
        return fields("集群名称", c.name(), "环境", c.environment(), "业务线", c.businessLine(), "负责人", c.owner(),
                "运维负责人", c.opsOwner(), "服务等级", c.serviceLevel(), "模式", c.mode(), "Redis 版本", c.redisVersion(),
                "IDC ID", c.idcId(), "状态", c.status());
    }
    public static Map<String, Object> application(ManagedApplication a) {
        if (a == null)
            return Map.of();
        return fields("应用编码", a.code(), "应用名称", a.name(), "负责人", a.owner(), "业务线", a.businessLine(), "状态", a.status());
    }
    public static Map<String, Object> command(OperationCommand c) {
        if (c == null)
            return Map.of();
        return fields("命令", c.commandName(), "分类", c.category(), "读写属性", c.accessMode(), "风险等级", c.riskLevel(),
                "启用", c.enabled(), "Key 参数位置", c.keyPosition(), "路由", c.routingPolicy(), "执行策略", c.approvalPolicy(),
                "Value 字节上限", c.maxValueBytes(), "允许数据类型", c.allowedDataTypesJson(), "Key 不存在策略", c.missingKeyPolicy(),
                "默认阻止", c.blockedByDefault());
    }
    public static String commandChange(OperationCommand before, OperationCommand after) {
        var next = command(after);
        if (before == null || !Objects.equals(before.parameterSchemaJson(), after.parameterSchemaJson()))
            next.put("参数定义", before == null ? "已配置（内容不记录）" : "已变更（内容不记录）");
        return change("命令配置：" + after.commandName(), command(before), next, after.changeReason());
    }
    public static Map<String, Object> sync(SyncTask t) {
        return fields("任务号", t.taskNo(), "源集群 ID", t.sourceClusterId(), "目标集群 ID", t.targetClusterId(),
                "源 DB", t.sourceDb(), "目标 DB", t.targetDb(), "状态", t.status(), "控制动作", t.desiredAction(),
                "操作速率上限", t.rateLimitOps(), "带宽上限", t.bandwidthLimitBytesPerSecond(), "Spool 上限", t.spoolLimitBytes(),
                "全量写入并发", t.fullApplyConcurrency(), "Pipeline 大小", t.fullApplyPipelineSize());
    }
}
