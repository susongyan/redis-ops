# ADR-006：数据校验采用抽样与大 Key 安全降级

- 状态：Superseded by [ADR-007](ADR-007-validation-key-retention-for-remediation.md)
- 日期：2026-07-29
- 关联契约：[架构契约](../architecture-contract.md)

## 决策

数据校验任务由 Platform 的异步租约 Worker 执行。任务只保存 Key SHA-256 摘要和元数据差异；对超过阈值的大 Key 不读取业务值，记为降级风险。

普通报告任务可带降级项完成；严格迁移/切换门禁遇到降级、未知类型或读取期间变化时必须返回 `INCONCLUSIVE`。

## 后果

`validation_task`、`validation_run`、`validation_shard_checkpoint` 和 `validation_difference` 由 V12 引入。未来的全量强校验、自动修复或 Stream Consumer Group 校验必须另行评审，并保持不记录业务内容的安全约束。

该 ADR 最初的“不保存原始 Key”约束已被 ADR-007 有限替代：仅差异记录保存 Key 名称用于数据订正；Redis Value 仍不得持久化或写入日志、事件、指标和审计。
