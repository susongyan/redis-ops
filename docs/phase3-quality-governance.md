# Phase 3：质量治理

状态：TTL 治理和数据清理治理闭环已实现，进入真实 Redis 验收阶段。

## 当前已实现：TTL 治理

TTL 治理任务通过 `ttl_governance_task` 保存集群、DB、Key Glob、目标 TTL、速率和最大 Key 数。
执行流程固定为：

```text
CREATED → DRY_RUN → AWAITING_APPROVAL → APPROVED → RUNNING → COMPLETED
                                      ↘ PAUSED ↗
                                      ↘ CANCELLED
```

- Dry Run 只使用 `SCAN`、`TTL` 和只读元数据，不修改 Redis。
- 只有 Dry Run 完成并进入 `AWAITING_APPROVAL` 后才能审批。
- 执行阶段使用 `EXPIRE`，每次写入前重新确认 TTL 仍为 `-1`；期间已被业务设置 TTL 的 Key 会跳过。
- 支持 Cluster 分 master 扫描、数据库游标 checkpoint、限速、最大 Key 数、暂停、恢复和取消。
- 任务、运行、分片游标和成功/跳过/失败统计均写入 MySQL。
- 所有创建、Dry Run、审批、开始、暂停和取消动作写入审计；请求继续使用 `Idempotency-Key` 与 `If-Match`。
- 页面入口为 `#/ttlGovernance`，展示任务状态、审批状态、运行统计和分片进度。

## 安全约束

- 不使用 `KEYS`，不读取完整 Value。
- 不允许绕过 Dry Run 和人工审批直接写 Redis。
- 写入仅执行 `EXPIRE`，不删除数据，不修改 Value。
- 目标集群不可用、数据库不合法、TTL 超出 1 秒至 365 天范围时拒绝创建。
- Key 仅用于 Redis 操作，不进入日志、审计和异步任务 payload。

## 数据清理治理

数据清理使用独立的 `cleanup_governance_task`，流程为：

```text
规则定义 → Dry Run → 影响报告 → 审批 → UNLINK 限速执行 → 校验 → 结果报告
```

- Dry Run 只使用 `SCAN` 和只读元数据，生成候选 Key 数和分片进度。
- 审批必须填写变更单号、业务确认或影响说明；审批说明会进入任务详情和审计记录。
- 执行阶段只调用 `UNLINK`，受影响 Key 数不得超过任务级 `impactLimit`。
- 支持限速、暂停、恢复、取消、失败关闭和 Cluster 分片 checkpoint。
- 页面入口为 `#/cleanupGovernance`，展示候选、已删除、跳过和失败数量。
- 已删除数据不提供平台侧自动恢复；恢复依赖业务侧备份或 Redis 同步能力。
