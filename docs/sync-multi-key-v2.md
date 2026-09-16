# 同步策略 v2：首批多 Key 准入

状态：阶段二已实现，未部署到当前运行进程。事务 / Lua effects 尚未接入，仍失败关闭。

## 启用和升级

- Platform 和 Worker 构建依赖 `sync-contract:0.2.0`。
- 创建任务请求 `commandPolicy.policyVersion` 显式传 `v2`；缺省、空值及历史任务仍按 v1。
- 能力接口 `GET /api/v1/sync-command-capabilities?policyVersion=v2` 展示条件性准入。
- 不修改运行中任务的策略或过滤范围。扩大范围需重新建立全量基线。
- 先完成所有 Worker 的阶段一兼容基线升级，再升级 v2 Worker，最后允许创建 v2 任务。
  阶段一 Worker 不领取 v2；更早的 Worker 没有此能力过滤，不能混用。
- Worker 实际连接源、目标 Redis 检查版本，限定 Redis 6.2 和 7.x；INFO 不可用或不兼容即阻塞。
  资产版本预检查不能替代该执行前检查。

## 支持边界

首批：MSETNX、RENAME、RENAMENX、SMOVE、LMOVE、RPOPLPUSH、COPY、BITOP、
SUNIONSTORE、SINTERSTORE、SDIFFSTORE、ZUNIONSTORE、ZINTERSTORE、ZDIFFSTORE、PFMERGE。

所有写 Key 在范围外则跳过；范围内写入依赖范围外读取则阻塞；同时修改范围内外 Key 则阻塞。
不拆分这些命令。Cluster 要求所有相关 Key 同 Slot，不能仅判断同节点。
保留命名空间、附加命令禁用策略始终有效。未知命令和不明确参数不透传。

COPY 的显式 DB 只能等于源任务 DB；发送到目标前移除该选项，使用连接已选择的目标 DB，
例如源 DB 5 到目标 DB 0。跨 DB COPY 阻塞，不将跨库操作猜测成当前库操作。

固定阻塞原因：

| 错误码 | 含义 |
| --- | --- |
| BLOCKED_OUTSIDE_INPUT_DEPENDENCY | 范围内结果依赖范围外 Key |
| BLOCKED_CROSS_SCOPE_WRITE | 一条命令同时修改范围内外 Key |
| BLOCKED_CROSS_SLOT | 不可拆分操作涉及多个 Slot |
| BLOCKED_CROSS_DATABASE | COPY 跨源 DB |
| BLOCKED_COMMAND_ARGUMENTS | 参数不满足已适配语法 |
| BLOCKED_RESERVED_NAMESPACE | 触达内部命名空间 |
| BLOCKED_UNSUPPORTED_REDIS_VERSION | 实际 Redis 版本不在适配范围 |
| BLOCKED_VERSION_CHECK_UNAVAILABLE | 执行前版本探测不可用 |

执行失败仍遵循[批次确认协议](sync-target-batch-confirmation.md)，不自动清除 pending 或盲目重放。
没有新增表或 migration；策略使用既有不可变 JSON 字段。不得把旧任务重新标为 v2。

## 开发验收记录（2026-09-17）

- 隔离 Redis 6.2 与 Redis 7.4 三主 Cluster：15 种命令原生执行与规划后执行的最终 DUMP 相同。
- 两种拓扑的批次确认、断线、接管与 COMMAND GETKEYS 测试各 14 项通过；Cluster 恢复 2 项通过。
- MySQL 实际领取 SQL 验证 v1 / v2 接纳，未知版本拒绝。
- 协议测试覆盖逐命令范围组合、COPY DB 映射、二进制 Key、跨 Slot、排除优先和附加禁用。
- Platform / Worker `mvn verify` 通过；接口覆盖 v1 / v2 能力差异。

这些结果不等于端到端复制流、Sentinel 切换、MOVED/ASK 或生产负载验收；后续阶段仍须补齐。
现有运行中的 Platform / Worker 未重启，页面创建默认仍是 v1。
