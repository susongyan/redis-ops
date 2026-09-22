# Architecture Decision Records

- [ADR-027 治理预检可跳过与可暂停](ADR-027-governance-optional-preflight.md)

- [ADR-026 Redis 5.0 源事务同步](ADR-026-redis5-source-transactions.md)

- [ADR-025 业务 Key 过滤与同步命令语义扩展（Accepted，分阶段实施）](ADR-025-sync-command-semantics.md)

- [ADR-024 审计变更详情](ADR-024-audit-change-details.md)

- [ADR-023 数据库命令准入与通用执行](ADR-023-database-command-admission.md)

- [ADR-022 操作身份快照](022-actor-snapshots.md)

ADR 记录会长期影响代码结构、运行方式、安全边界或迁移成本的决策。每个 ADR 一经接受不修改
历史结论；若需要改变，新增一个 superseding ADR，并在旧记录中链接。

实现专题：[Phase 2：采集、风险扫描与告警](../phase2-collector-risk-alert.md)。

## 状态

- `Proposed`：待讨论，不能作为实现依据。
- `Accepted`：已作为当前架构约束。
- `Superseded`：已被后续 ADR 取代，但保留历史原因。
- `Deprecated`：不再推荐，但尚未完全移除。

## 索引

| ADR | 状态 | 决策 |
|---|---|---|
| [ADR-020](ADR-020-author-package-prefix.md) | Accepted | 作者命名空间与 Maven groupId 统一 |
| [ADR-001](ADR-001-modular-monolith-and-separate-sync-worker.md) | Accepted | 模块化单体控制面与独立 Sync Worker |
| [ADR-002](ADR-002-mysql-lease-control-plane.md) | Accepted | 共享 MySQL 租约控制面与目标 Redis 最终 fence |
| [ADR-003](ADR-003-cluster-owned-encrypted-connection-secret.md) | Accepted | 集群内置加密连接秘密 |
| [ADR-004](ADR-004-fail-closed-sync-safety.md) | Accepted | 同步失败关闭与人工确认重建 |
| [ADR-005](ADR-005-utc-persistence-time-semantics.md) | Accepted | UTC 持久化时间语义 |
| [ADR-006](ADR-006-data-validation-sampling-and-big-key-safety.md) | Superseded | 数据校验采用抽样与大 Key 安全降级 |
| [ADR-007](ADR-007-validation-key-retention-for-remediation.md) | Accepted | 差异保留 Key 名称用于排障与数据订正 |
| [ADR-008](ADR-008-phase2-collector-and-observability.md) | Accepted | Phase 2 内置 Collector 与 Prometheus 指标边界 |
| [ADR-009](ADR-009-internal-key-retention-for-risk-and-slowlog.md) | Accepted | 企业内部风险扫描与慢日志保留原始 Key |
| [ADR-010](ADR-010-phase3-quality-governance.md) | Accepted | Phase 3 质量治理采用 Dry Run 与审批门禁 |
| [ADR-012](ADR-012-model-agnostic-ai-agent-integration.md) | Accepted | AI 分析采用模型无关抽象，A2A/ACP 通过适配器接入 |
| [ADR-015](ADR-015-analysis-advisory-delivery.md) | Accepted | 按请求触发辅助分析，外部失败直报，结果仅供人工参考 |
| [ADR-016](ADR-016-task-worker-observability.md) | Accepted | 任务级 Worker 实例、机器 IP 与租约状态观测 |
| [ADR-017](ADR-017-bounded-key-distribution.md) | Accepted | Platform 内按需执行、有界内存的 Key 分布分析 |
| [ADR-018](ADR-018-scan-batch-throttling.md) | Accepted | 分布分析按 SCAN COUNT 与批次最小间隔限速，旧参数只读兼容 |
| [ADR-019](ADR-019-local-identity-and-authentication-extensions.md) | Accepted | 本地用户授权与企业认证扩展边界（实现进行中） |
| [ADR-020](ADR-020-local-password-minimum.md) | Accepted | 本地密码最短六个字符，保留 BCrypt 字节上限与其他认证保护 |

## 新建模板

- [ADR-014：项目内部模块和包命名](ADR-014-project-local-package-names.md)（Accepted）

- [ADR-013：最小同步契约与独立仓库构建](ADR-013-minimal-sync-contract-repositories.md)（Accepted）

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
