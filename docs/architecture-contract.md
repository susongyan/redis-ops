# 架构契约

> 状态：Active  
> 适用范围：Redis Governance Platform 全部模块与后续服务拆分  
> 依据：[项目设计总览](architecture.md)、[同步 Worker 生命周期](sync-worker-lifecycle.md)、
> [控制面与 Worker 分离](sync-control-worker-separation.md)

本契约将总体设计中的原则转化为可验证约束。它不替代详细设计；发生冲突时，安全边界优先，
并通过 ADR 更新本契约。

## 1. 平台边界

| 分类 | 契约 |
|---|---|
| 平台定位 | 平台是 Redis 的旁路治理控制面，不承载业务 Redis 流量。 |
| 已实现基线 | 资产/应用绑定、拓扑发现、跨 IDC 主备关系、原生同步 Worker、同步生命周期、fence/checkpoint、全量进度、数据校验报告与审计。 |
| 后续领域 | Collector、风险扫描、TTL/清理治理、告警、审批、AI 分析。它们只能按本文的安全和数据边界接入。 |
| 明确非目标 | Redis 部署、扩缩容、slot rebalance、配置下发、自动重启、业务代理、自动 DNS/流量切换、双向写入。 |

## 2. 逻辑所有权和依赖

```text
Web Console → Platform API → Application Service → Domain Port
                                      ↓
                         MySQL / Redis / Audit Adapter

Platform（控制面） ── MySQL 控制命令、状态、审计 ──► Sync Worker（数据面）
Sync Worker ── PSYNC / RDB / RESP ──► Source Redis / Target Redis
```

1. **Domain 不依赖框架。** 框架、MyBatis、Socket、Redis Client 只能位于 Infrastructure、API、
   Bootstrap 或 Sync Service。
2. **控制面不执行数据复制。** Platform 负责创建、预检查、确认、状态和审计；Sync Worker 负责
   协议、spool、目标写入、运行指标与 checkpoint 摘要。
3. **数据面不改变业务拓扑。** Sync Worker 不交换主备角色、不调用 DNS/代理、不修改应用配置，
   也不得绕过 Platform 对目标清空和写隔离的确认。
4. **模块可拆分但不预拆分。** 当前是模块化单体 + 独立 Sync Worker。新的网络服务只有在独立
   扩缩容、故障隔离、独立团队或网络域边界成立时才允许引入，并需要 ADR。

## 3. 数据事实与一致性契约

| 事实 | 最终来源 | 约束 |
|---|---|---|
| 资产、关系、审批、审计、任务期望状态 | MySQL | Platform 拥有写入权；核心资源用乐观锁。 |
| Worker 运行资格 | MySQL runtime lease | 领取时递增 fencing generation；过期可安全接管。 |
| 同步数据已应用 offset | 目标 Redis checkpoint | MySQL 只保存摘要；崩溃恢复必须以 checkpoint 为准。 |
| 当前目标写资格 | 目标 Redis fence | 业务命令、checkpoint 与 fence 校验在同一原子边界内提交。 |
| 全量进度 | `sync_full_progress` | 仅观测，按 task/epoch/channel/lane 单调累积；写入故障不得降低同步安全。 |
| 高频指标 | Prometheus/TSDB（规划） | MySQL 仅保存低频摘要，禁止把高频采集点持续写入事务库。 |

所有时间使用 UTC `Instant` 语义，JDBC URL 固定 `serverTimezone=UTC`。已执行的 Flyway
migration 是不可变历史；任何 schema 演进均新增版本化 migration。

## 4. 同步安全契约

1. 新全量同步仅允许 `FULL_AND_INCREMENTAL`；恢复增量必须拥有已有 checkpoint。
2. 启动全量前必须有 10 分钟内通过的预检查、目标写隔离确认、目标清空确认和任务号二次确认。
3. 不可安全恢复（backlog 不足、replId 不兼容、spool 损坏或全量 spool 不可用）时进入
   `BLOCKED_REQUIRES_FULL_RESYNC`，绝不自动清空目标重做。
4. 旧 Worker 在 GC、租约丢失或 generation 落后后，必须停止源读取、ACK、目标写入和
   checkpoint 更新；新 Worker 先发布更高 generation fence，后恢复。
5. 默认未知命令、Module 数据、未知 RDB 编码、不安全过滤边界和不等价跨 slot 命令均失败关闭，
   记录命令名、offset、数量和 hash 摘要，禁止记录原始 key/value。
6. 保留命名空间 `__redis_ops_sync_*` 不得同步为业务数据；发现业务冲突时预检查失败。
7. 全量 `RESTORE ... REPLACE ABSTTL` 可以重放，但每个并发 lane 仍必须经过 fence，避免旧快照
   覆盖接管后的数据。

## 5. API、事件和并发契约

- 所有可重试写 API 必须要求 `Idempotency-Key`；资源更新/控制动作必须要求 `If-Match`。
- `X-Operator` 只记录审计身份，不能替代 RBAC。RBAC 尚未实现时，不得在 API 文案中暗示权限
  已被强制执行。
- API 响应有稳定 envelope 和 requestId；新增字段向后兼容，删除/语义改变必须版本化或经 ADR。
- 异步命令 payload 至少包含任务 ID、命令 ID 与非秘密参数；命令领取和执行必须幂等。
- 事件至少可关联 taskId、clusterId、runtimeId、generation、操作人和发生时间；审计为追加写。

## 6. 秘密、隐私与风险动作契约

- Redis 密码仅以 AES-256-GCM 密文保存；主密钥仅来自部署配置，Platform 与 Sync Worker 使用同一
  密钥环，但不得通过 REST、任务 payload 或日志传输明文。
- 连接配置消费者必须通过 `RedisConnectionProfileProvider` 获取可清零的密码内存；不得直接
  查询密文表或自行解密。
- API、日志、异常、审计、幂等记录、spool 元数据和指标中禁止出现密码、密文、主密钥或完整
  Redis value。
- 扫描/校验优先保存 hash、摘要和 pattern；任何保存原始 key 的场景必须有脱敏、保留期和访问
  控制设计。
- 未来 TTL/清理治理必须遵循：规则版本 → Dry Run → 影响上限 → 审批 → 限速执行 → 校验 →
  审计。删除优先 `UNLINK`。

## 7. 可观测性、可靠性与降级

- 每个执行路径应能关联 requestId、jobId、taskId、clusterId、runtimeId 和 workerId。
- 运行异常必须可定位阶段、错误码和操作人，不泄露敏感数据。
- MySQL 超过租约安全窗口不可用时，Worker 必须停止目标应用；不能脱离控制面无限执行。
- API 可横向扩展；Worker 通过 lease、fence 与 checkpoint 保证单一有效写者。
- 全量进度页面的总体百分比是观测估算：RDB 接收 30%、解析 20%、RESTORE 50%。它不可作为
  数据一致性或切换依据；切换使用 checkpoint、RPO 和预检查规则。

## 8. 变更门槛

下列变化必须新增 ADR，并同步更新本契约、测试与运行文档：

- 新增独立部署服务、消息队列、共享存储或新的数据所有者。
- 改变 MySQL/Redis checkpoint/fence/lease 的事实优先级。
- 改变同步命令兼容、目标清空、自动恢复或切换的安全策略。
- 改变凭证存储、密钥来源、数据脱敏或审计保留策略。
- 引入 RBAC、审批、Collector、治理执行或 AI 对生产系统的任何写能力。
