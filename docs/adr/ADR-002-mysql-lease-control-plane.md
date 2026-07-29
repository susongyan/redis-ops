# ADR-002：共享 MySQL 租约控制面与目标 Redis 最终 Fence

- 状态：Accepted
- 日期：2026-07-29
- 关联契约：架构契约第 3、4 节

## 背景

同步 Worker 可能长时间 GC、崩溃或被新实例接管。仅使用 MySQL 租约只能判断调度资格，无法
阻止旧 Worker 已发出的 Redis pipeline 在新 Worker 接管后写入目标。

## 决策

Platform 写控制命令和期望状态，Sync Worker 通过 MySQL 条件更新领取 runtime 租约并递增
`fencing_generation`。新 owner 在目标 Redis 发布带 generation/runtime 的 fence；业务命令、
checkpoint 与 fence 校验使用同一 `WATCH/MULTI/EXEC` 原子边界。

目标 Redis checkpoint 是已应用 offset 的最终事实，MySQL checkpoint 仅为监控摘要。

## 后果

- 旧 Worker 的事务要么在新 fence 前完整生效，要么在 `EXEC` 时冲突失败；不会与新 Worker
  并发有效写入。
- Worker 必须在 lease 安全截止、fence 冲突或 MySQL 不可用超出窗口时失败关闭。
- 共享 MySQL 是当前控制通道，未来可在 `SyncCommandPort` 后替换为 MQ/REST，但不改变 fence 与
  checkpoint 安全模型。

## 替代方案与取舍

- 仅数据库锁：无法约束 Redis 已发送 pipeline。
- 仅 Redis 锁：不能持久表达控制意图、审计和跨实例领取。
- Platform 直接向 Worker HTTP 下发：仍需处理重复调用与回调丢失，且无法消除 Worker 的秘密读取
  与状态恢复问题。

## 落地与验证

必须覆盖 lease 过期竞争、`WATCH` 后 GC、`EXEC` 前后崩溃、spool 恢复和 generation 不回退。
