# 操作人展示与身份快照

新操作由后端解析认证主体，保存稳定的 `user:<id>`，同时保存当时的账号和显示名称。
页面优先展示 `显示名称（账号）`，同名只展示一次。客户端不能指定或覆盖操作者。

覆盖范围：Console 发起人、审批／确认人和执行人；审计操作人；同步任务事件；主备切换发起人；
告警确认人；命令策略修改人。资产、治理和配置等已有审计记录也使用统一快照。
系统与 Worker 操作保留系统标识，不归属为当前查看页面的人。

旧记录没有姓名快照时继续展示原始主体（例如 `user:1`），不使用当前用户资料伪造历史。
修改显示名称不影响旧快照。审计筛选支持账号、显示名称或原始主体的精确匹配。
API 新增 `operatorSnapshot`、`approverSnapshot`、`executorSnapshot`、`acknowledgedBySnapshot`、
`updatedBySnapshot`（可空 JSON 字符串，内容为 userId/login/displayName），原字段保留。
Console 新增 `executorName`，与发起人、审批人分别记录。

已有环境启动新版本由 Flyway 执行 V32；新环境使用 `sql/latest/redis-governance-init.sql`。
无历史数据回填或删除。部署失败应保留可空新列，回退应用而非删除审计数据。
