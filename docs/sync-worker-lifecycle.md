# Redis 同步 Worker 管理流程与生命周期设计

## 1. 文档目标

本文档定义自研 Redis 同步 Worker 的管理边界、状态模型、控制命令、运行租约、故障恢复和
可观测性约定。它回答以下问题：

- Platform 和 Sync Worker 分别管理什么。
- 一个同步任务从创建到结束经历哪些状态。
- START、PAUSE、RESUME 等命令如何可靠送达且避免重复执行。
- Worker 崩溃、租约失效、Redis 断线和 MySQL 中断后如何恢复。
- checkpoint、spool、MySQL 状态冲突时以谁为准。
- 主备切换工作流如何与同步任务衔接。

本文档描述目标设计。当前代码完成度和目标设计之间的差距见第 13 节。

## 2. 核心设计原则

1. **管理面与数据面分离**：Platform 管理任务和关系，Sync Worker 执行复制。
2. **期望状态与实际状态分离**：操作请求不能直接伪造 Worker 已完成某个运行阶段。
3. **控制命令至少一次投递**：重复投递必须安全，不能重复清空目标或启动两个 Runner。
4. **单任务单执行者**：运行租约和 fencing generation 阻止多个 Worker 同时应用数据。
5. **目标 checkpoint 是正确性最终事实**：MySQL offset 只用于查询和监控。
6. **先持久化再 ACK**：增量数据写入加密 spool 并 fsync 后，才能向源端 ACK received offset。
7. **失败关闭**：遇到未知 RDB 类型、不可安全拆分命令或 checkpoint 冲突时停止，不静默跳过。
8. **秘密不进入控制链路**：控制命令和任务 payload 只保存资源 ID，Worker 本地读取并解密密码。
9. **切换不等于流量变更**：Worker 负责追平和停止复制，不修改 DNS、代理或应用配置。

## 3. 组件架构

![Redis 同步 Worker 架构](images/sync-worker-architecture.svg)

### 3.1 Platform 管理面

Platform 负责：

- 创建同步任务，校验长期关系或临时源目标。
- 保存 DB 映射、过滤规则、限速和 spool 上限。
- 接收 PRECHECK、START、PAUSE、RESUME、FINISH、CANCEL、RATE_LIMIT 请求。
- 校验 `Idempotency-Key`、`If-Match`、操作人和显式危险操作确认。
- 写入控制命令、任务期望状态、审计事件。
- 展示 runtime、channel、metric、precheck 和事件。
- 编排主备切换，并在条件满足后交换主备关系。

Platform 不负责：

- 建立 PSYNC 长连接。
- 解析 RDB 或增量命令。
- 保存 Redis 明文密码到任务 payload。
- 直接推进 FULL_SYNCING、INCR_SYNCING、CAUGHT_UP 等实际运行状态。
- 自动修改业务流量入口。

### 3.2 Sync Worker 数据面

Sync Worker 负责：

- 领取控制命令并维护短期命令租约。
- 竞争任务运行租约并生成 fencing generation。
- 从集群资产和秘密表读取连接信息，在本进程内解密。
- 发现 Standalone、Sentinel 或 Cluster 源通道。
- 执行 AUTH、REPLCONF、PSYNC、RDB 全量和增量命令复制。
- 管理本地加密 spool、目标 checkpoint、限速和故障恢复。
- 写 runtime、channel、metric 和同步事件。
- 根据真实执行结果上报任务状态。

Sync Worker 不允许直接修改 `cluster_relation` 的主备方向。

## 4. 四类持久状态及事实优先级

| 数据 | 存储位置 | 用途 | 正确性优先级 |
|---|---|---|---|
| 目标 checkpoint | 目标 Redis | 已原子应用到目标的最终 offset | 最高 |
| spool segment 和 manifest | Worker 本地持久卷 | 已接收但可能尚未应用的数据 | 第二 |
| channel checkpoint 摘要 | MySQL | received/applied offset、通道状态和页面查询 | 第三 |
| runtime | MySQL | 当前执行实例、租约、阶段、spool 用量 | 管理状态 |
| sync task | MySQL | 业务配置、期望动作和对外状态 | 管理状态 |
| async job | MySQL | 一次控制命令的可靠投递与重试 | 控制状态 |

恢复时必须按以下顺序取值：

1. 读取目标 checkpoint，确定真正的 `appliedOffset`。
2. 校验本地 spool manifest，确定可重放到哪个 `receivedOffset`。
3. 校验源端 replId 和 backlog 是否仍支持 PSYNC。
4. 更新 MySQL channel 摘要；不得反过来用 MySQL offset 覆盖目标 checkpoint。

## 5. 三条独立生命周期

### 5.1 同步任务生命周期

`sync_task.status` 是面向用户的业务状态。

![同步任务状态生命周期](images/sync-worker-state-lifecycle.svg)

| 状态 | 含义 | 状态拥有者 |
|---|---|---|
| `CREATED` | 任务配置已保存，尚未检查 | Platform |
| `CHECKING` | 预检查命令等待执行或执行中 | Platform 发起，Worker 完成 |
| `READY` | 最近十分钟内预检查通过，可确认启动 | Worker 上报 |
| `STARTING` | 已确认写隔离和目标清空，等待 Worker 启动 | Platform 发起 |
| `FULL_SYNCING` | 正在接收或应用全量 RDB | Worker 上报 |
| `INCR_SYNCING` | 正在持续应用增量命令 | Worker 上报 |
| `CAUGHT_UP` | 所有通道已追平且满足稳定性条件 | Worker 上报 |
| `PAUSING` | 正在停止目标应用并稳定 checkpoint | Platform 发起 |
| `PAUSED` | 目标应用已暂停；源数据可继续进入 spool | Worker 上报 |
| `RESUMING` | 正在校验 checkpoint、spool 和 PSYNC 条件 | Platform 发起 |
| `STOPPING` | 源已写隔离，正在追最终 offset | Platform 发起 |
| `BLOCKED` | 需要人工处理或重新确认全量 | Worker 上报 |
| `FAILED` | 本轮运行因不可恢复的执行错误失败 | Worker 上报 |
| `FINISHED` | 已按 FINISH 流程追平并正常结束 | Worker 上报，终态 |
| `CANCELLED` | 操作人取消，不保证目标形成完整副本 | Platform 发起，终态 |

`CAUGHT_UP` 不是终态。源端产生新写入后，任务可以回到 `INCR_SYNCING`。

### 5.2 控制命令生命周期

每个用户操作对应一条 `async_job`。控制命令状态独立于同步任务状态：

```text
PENDING -> RUNNING -> SUCCEEDED
                   \-> RETRY -> RUNNING
                   \-> FAILED
```

约定：

- `Idempotency-Key` 唯一标识一次业务操作，重复请求返回同一结果。
- Job `SUCCEEDED` 表示控制动作已被持久接受并产生预期效果，不表示长时间同步任务已经结束。
- START 只有在运行租约获取、目标预处理完成且 Runner 成功注册后才能标记成功。
- 长时间目标清空期间必须续租 Job；租约失效的旧实例不得继续执行。
- Job 重试前必须读取任务、runtime 和事件，判断动作是否已经完成。
- payload 只允许任务 ID 和非秘密参数，不保存密码、密文或原始 Key/Value。

### 5.3 Worker Runtime 生命周期

`sync_runtime` 表示某次任务当前由哪个 Worker 执行：

```text
IDLE
  -> CLAIMED
  -> PREPARING_TARGET
  -> FULL_RECEIVING / FULL_APPLYING
  -> INCREMENTAL
  -> PAUSED
  -> DRAINING
  -> FINISHED

任意运行阶段 -> BLOCKED / FAILED / LEASE_LOST / CANCELLED
```

Runtime 规则：

- Worker 每 5 秒续租，默认租期 30 秒。
- 获取租约时递增 `fencing_generation`。
- 续租失败后立即停止读取和目标应用，不得运行到租约自然过期以后。
- 新实例接管时使用新的 runtimeId 和 generation，旧 generation 的 checkpoint 提交必须失败。
- `phase` 比 `sync_task.status` 更细，只用于运维诊断，不作为用户直接操作入口。

## 6. 标准运行流程

![同步 Worker 标准运行流程](images/sync-worker-runtime-flow.svg)

### 6.1 创建与预检查

1. Platform 创建 `sync_task`，初始状态为 `CREATED`。
2. 操作人请求预检查，Platform 原子执行：
   - 校验 `If-Match`。
   - 状态改为 `CHECKING`。
   - 写 `SYNC_PRECHECK` Job。
   - 写审计记录。
3. Worker 领取 Job，检查：
   - 源、目标集群状态和连接认证。
   - 部署模式、Redis 版本和 DB 映射。
   - 源复制能力、backlog、磁盘和网络。
   - 目标写隔离、保留 Key 冲突和目标空间。
   - Module、TLS、未知持久化格式等不支持项。
   - Cluster slot、Sentinel master 和时钟偏差。
4. Worker 保存带十分钟有效期的 `sync_precheck_report`。
5. 全部通过则任务进入 `READY`，否则进入 `FAILED` 并记录错误码。

预检查只是一份短期快照。START 时必须重新执行关键安全检查，不能完全信任旧报告。

### 6.2 启动全量同步

1. 操作人提交 START，并明确确认：
   - `writeFenced=true`
   - 写隔离说明或变更单
   - `allowTargetFlush=true`
   - 精确填写任务编号
2. Platform 校验预检查未超过十分钟，将任务置为 `STARTING`，写 `SYNC_START` Job。
3. Worker 领取命令并获取任务运行租约。
4. Worker 重检源目标、写隔离确认、目标保留 Key 和自身磁盘空间。
5. Worker 将 runtime 置为 `PREPARING_TARGET`，逐目标节点执行清空：
   - Standalone/Sentinel：清空指定 DB。
   - Cluster：清空所有当前 master 的 DB 0。
   - 每个节点结果单独写事件，不静默重试失败节点。
6. Worker 建立源复制通道：
   - Standalone：一个通道。
   - Sentinel：当前 master 一个通道，并监控 master 变化。
   - Cluster：每个 master 一个通道，记录 slotRanges。
7. 每个通道执行 `AUTH -> REPLCONF -> PSYNC`。
8. FULLRESYNC 时接收 RDB 到加密 spool；fsync 后才更新 received offset 和发送 ACK。
9. 流式解析 RDB，通过 `RESTORE REPLACE ABSTTL` 写入目标。
10. 全量结束后切换到增量命令流，任务进入 `INCR_SYNCING`。

目标清空和创建新 fullSyncEpoch 必须每个 epoch 只执行一次。Job 重试不能再次清空已经开始写入的目标。

### 6.3 增量同步与追平

每条或每批增量命令经历：

1. 从 PSYNC 流读取完整 RESP 命令并计算命令结束 offset。
2. 执行保留命名空间过滤和 include/exclude 过滤。
3. 根据目标模式拆分安全的多 Key 命令；不可等价拆分则进入 `BLOCKED_FILTER_BOUNDARY`。
4. 写入加密 spool 并 fsync。
5. 按目标节点或 slot 批量应用业务命令。
6. 业务命令和目标 checkpoint 在同一个原子提交中完成。
7. checkpoint 成功后推进 applied offset，并删除不再需要的 spool segment。
8. 每 5 秒将低频 channel 和 metric 摘要写入 MySQL。

所有通道连续三个采样周期满足目标 RPO，且指标新鲜度、时钟偏差合格时进入 `CAUGHT_UP`。

### 6.4 暂停与恢复

PAUSE 是“暂停目标应用”，不是立刻断开源复制：

1. Platform 将任务置为 `PAUSING` 并写命令。
2. Worker 停止调度新的目标批次，等待在途批次和 checkpoint 提交完成。
3. Worker 继续接收源数据并写加密 spool。
4. runtime 进入 `PAUSED`，任务进入 `PAUSED`。
5. spool 达 70% 告警；达 90% 停止从源读取。

RESUME 时：

1. Worker 验证运行租约和 generation。
2. 读取目标 checkpoint 和 spool manifest。
3. 先重放 spool，再尝试从最后 received offset 执行 PSYNC。
4. backlog 不足时进入 `BLOCKED_REQUIRES_FULL_RESYNC`，不得自行清空目标。
5. 恢复成功后返回 `FULL_SYNCING`、`INCR_SYNCING` 或 `CAUGHT_UP`。

### 6.5 正常结束与取消

FINISH 用于有序结束：

1. 外部系统停止源端业务写入，操作人确认 `sourceWriteFenced=true`。
2. 任务进入 `STOPPING`，Worker 记录每个源通道最终 offset。
3. 等待所有目标 checkpoint 追到最终 offset。
4. 关闭 PSYNC、刷盘、释放租约，任务进入 `FINISHED`。

CANCEL 用于放弃任务：

- 停止读取和应用，关闭连接并释放租约。
- 保留审计、事件、checkpoint 和失败诊断信息。
- 清理当前任务本地 spool。
- 不自动回滚或清空已写入目标的数据。
- 任务进入 `CANCELLED`。

## 7. Worker 启动、接管和停机

### 7.1 实例启动

1. 校验数据库、数据目录、密钥环和磁盘权限。
2. 生成唯一 `instanceId`，注册健康和 Prometheus 指标。
3. 扫描本地 spool manifest，标记孤儿目录和待恢复任务。
4. 查询本实例可接管的过期 runtime。
5. 开始轮询控制 Job；不得仅凭本地目录自动恢复任务。

### 7.2 崩溃接管

新 Worker 只能在旧租约过期后获取任务：

1. 原子更新 runtime，取得新 generation。
2. 从目标 Redis 读取 checkpoint。
3. 校验本地持久卷是否包含对应 spool；没有共享持久卷时尝试 PSYNC。
4. 若目标 checkpoint 到源 backlog 范围内，执行部分恢复。
5. backlog 不足则进入 `BLOCKED_REQUIRES_FULL_RESYNC`。
6. 接管事件记录旧实例、新实例、generation、恢复来源和最终决定。

### 7.3 优雅停机

- 停止领取新 Job。
- 将正在运行任务标记为 draining。
- 停止目标批次，提交在途 checkpoint。
- fsync spool，停止源读取并释放运行租约。
- 超过停机宽限时间仍未安全停止时，让租约过期，不强制提交未完成批次。

## 8. 幂等、并发和 Fencing

| 风险 | 防护 |
|---|---|
| 用户重复点击 | `Idempotency-Key` |
| 用户基于旧页面操作 | `If-Match` 和任务版本 |
| Job 重复投递 | 动作前读取 task/runtime/event，按动作和 epoch 去重 |
| 两个 Worker 领取同一命令 | async job 短租约 |
| 两个 Worker 执行同一任务 | runtime 租约 |
| 旧 Worker 恢复后继续写 | fencing generation + 目标 checkpoint WATCH |
| Worker 在目标提交后、MySQL 更新前崩溃 | 以目标 checkpoint 恢复 |
| START 重试重复清空目标 | fullSyncEpoch + 目标准备事件唯一约束 |
| 非幂等命令重复执行 | 业务命令与 checkpoint 原子提交 |

建议增加数据库唯一约束：

- `(task_id, action, request_id)`：控制动作事件去重。
- `(task_id, full_sync_epoch, target_node, stage)`：目标清空阶段去重。
- `(task_id, channel_id)`：通道唯一。

## 9. 阻塞与失败分类

`BLOCKED` 表示需要明确的恢复决策，不能自动重试：

- `BLOCKED_REQUIRES_FULL_RESYNC`
- `BLOCKED_FILTER_BOUNDARY`
- `BLOCKED_UNSUPPORTED_RDB_TYPE`
- `BLOCKED_UNSUPPORTED_COMMAND`
- `BLOCKED_RESERVED_KEY_CONFLICT`
- `BLOCKED_TARGET_CHECKPOINT_CONFLICT`
- `BLOCKED_SPOOL_LIMIT`
- `BLOCKED_VERSION_INCOMPATIBLE`
- `BLOCKED_TARGET_NOT_FENCED`

`FAILED` 表示当前执行轮次失败，但允许重新预检查：

- 密钥无法解密。
- 数据库 schema 不兼容。
- 本地 spool 损坏且无法恢复。
- 目标清空部分失败。
- Runner 内部异常或配置错误。

网络抖动、短暂 Redis 断线和临时 MySQL 错误应先进行有限次数退避重试，不立即进入 FAILED。
所有错误日志禁止输出密码、密文、原始 Key 和 Value。

## 10. RPO 与可观测性

每个通道至少暴露：

- replicationId、receivedOffset、appliedOffset、offsetGap。
- source/apply ops/s 和 bytes/s。
- backlog、spool bytes、spool 使用率。
- timestamp lag、estimated lag、RPO 计算方式和可信度。
- reconnect、FULLRESYNC 次数和最后错误码。
- runtime lease owner、leaseUntil、generation 和续租失败次数。

任务级 RPO 取所有源通道最大值。MySQL 每 5 秒保存一次低频摘要，Prometheus 保留高频指标。

健康检查分为：

- `liveness`：进程和调度线程存活。
- `readiness`：MySQL、密钥环和数据目录可用，可以领取新任务。
- 单任务健康：租约、源连接、目标连接、spool 和 checkpoint 是否正常。

## 11. API、命令与状态映射

| API | 控制 Job | 请求后的任务状态 | Worker 成功结果 |
|---|---|---|---|
| `POST /prechecks` | `SYNC_PRECHECK` | `CHECKING` | `READY` 或 `FAILED` |
| `POST /start` | `SYNC_START` | `STARTING` | `FULL_SYNCING` |
| `POST /pause` | `SYNC_PAUSE` | `PAUSING` | `PAUSED` |
| `POST /resume` | `SYNC_RESUME` | `RESUMING` | 运行态或 `BLOCKED` |
| `POST /finish` | `SYNC_FINISH` | `STOPPING` | `FINISHED` |
| `POST /cancel` | `SYNC_CANCEL` | `CANCELLED` | runtime 释放 |
| `PUT /limits` | `SYNC_RATE_LIMIT` | 原状态 | Runner 使用新限速 |

所有写接口要求 `Idempotency-Key` 和 `If-Match`。`X-Operator` 只用于审计，不承担权限认证。

## 12. 与主备切换的衔接

长期主备切换只允许使用处于 `CAUGHT_UP` 的关联任务，并要求：

1. 连续三个采样周期满足关系目标 RPO。
2. 指标新鲜度不超过 5 秒，时钟偏差不超过 2 秒。
3. 外部停止旧主写入并确认 source fence。
4. Worker 执行 FINISH，追到所有通道最终 offset。
5. 切换工作流进入 `WAITING_EXTERNAL_SWITCH`。
6. 外部流量切换完成后，Platform 交换主备关系并创建反向全量任务。

Worker 只报告“最终 offset 已追平”，不自行交换关系，也不启动未经 Platform 确认的反向同步。

## 13. 当前实现与目标差距

当前已经具备：

- 独立 Sync Service 启动模块。
- V7 runtime、channel、precheck、metric 数据结构和 Repository。
- 控制 API、异步 Job 领取框架、预检查和目标清空基础代码。
- RESP、PSYNC 握手、部分 RDB 解析、Key 过滤和命令规划基础库。

当前尚未具备：

- `NativeSyncRunnerManager` 和真实 Runner，Sync Service 暂时不能编译运行。
- 全量 RDB 接收、目标 RESTORE 和增量命令应用闭环。
- 目标原子 checkpoint 和 generation fencing。
- 加密 spool、fsync、segment 回收和断点续传。
- 租约定时续期、优雅停机和实例接管。
- Sentinel failover、Cluster 多 master 通道和任意目标路由。
- RPO 心跳、Prometheus 指标和低频指标采集器。
- 完整预检查、逐节点清空审计及真实 Redis 集成测试。

因此当前代码属于“控制骨架和协议 PoC”，尚不是可部署的同步 Worker。

## 14. 分阶段验收

### M1：管理生命周期闭环

- Sync Service 可编译、启动并通过 readiness。
- 控制 Job 可幂等领取、续租、完成和失败重试。
- runtime 可竞争、续租、释放并阻止双实例。
- 所有任务状态只能按状态机迁移。

### M2：Standalone 最小复制闭环

- Standalone 到 Standalone 完成全量和增量。
- 非幂等命令在故障注入后不重复应用。
- 暂停、spool、恢复和部分重同步有效。

### M3：高可用与 Cluster

- Sentinel master 切换自动重连。
- Cluster 每 master 独立通道并正确路由目标 slot。
- 多实例接管、磁盘满和 MySQL 中断测试通过。

### M4：切换验收

- RPO 连续稳定判定。
- source fence、最终 offset、外部流量确认和反向全量流程通过。
- 全链路审计不包含密码、密文、原始 Key 或 Value。
