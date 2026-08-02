# Architecture Decision Records

ADR 记录会长期影响代码结构、运行方式、安全边界或迁移成本的决策。每个 ADR 一经接受不修改
历史结论；若需要改变，新增一个 superseding ADR，并在旧记录中链接。

## 状态

- `Proposed`：待讨论，不能作为实现依据。
- `Accepted`：已作为当前架构约束。
- `Superseded`：已被后续 ADR 取代，但保留历史原因。
- `Deprecated`：不再推荐，但尚未完全移除。

## 索引

| ADR | 状态 | 决策 |
|---|---|---|
| [ADR-001](ADR-001-modular-monolith-and-separate-sync-worker.md) | Accepted | 模块化单体控制面与独立 Sync Worker |
| [ADR-002](ADR-002-mysql-lease-control-plane.md) | Accepted | 共享 MySQL 租约控制面与目标 Redis 最终 fence |
| [ADR-003](ADR-003-cluster-owned-encrypted-connection-secret.md) | Accepted | 集群内置加密连接秘密 |
| [ADR-004](ADR-004-fail-closed-sync-safety.md) | Accepted | 同步失败关闭与人工确认重建 |
| [ADR-005](ADR-005-utc-persistence-time-semantics.md) | Accepted | UTC 持久化时间语义 |
| [ADR-006](ADR-006-data-validation-sampling-and-big-key-safety.md) | Superseded | 数据校验采用抽样与大 Key 安全降级 |
| [ADR-007](ADR-007-validation-key-retention-for-remediation.md) | Accepted | 差异保留 Key 名称用于排障与数据订正 |
| [ADR-008](ADR-008-phase2-collector-and-observability.md) | Accepted | Phase 2 内置 Collector 与 Prometheus 指标边界 |
| [ADR-009](ADR-009-internal-key-retention-for-risk-and-slowlog.md) | Accepted | 企业内部风险扫描与慢日志保留原始 Key |

## 新建模板

```markdown
# ADR-NNN：决策标题

- 状态：Proposed
- 日期：YYYY-MM-DD
- 决策者：
- 关联契约：

## 背景

## 决策

## 后果

## 替代方案与取舍

## 落地与验证
```
