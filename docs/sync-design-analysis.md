# 数据同步设计分析（现状）

> 状态：已完成
> 范围：redis-ops-sync 数据面整体设计——控制面编排、全量/增量链路、正确性机制、安全、背压与可观测性
> 分析基准：2026-10-20 的数据面代码状态；所有 `文件:行号` 引用以该时点为准
> 关联文档：[big-key-split-design.md](big-key-split-design.md)（大 key 拆分，设计评审中，本文 §10-#5 关联）、[apollo-integration.md](apollo-integration.md)

## 1. 系统定位与协作模型

三系统分工：**Platform**（管理端，业务控制面）→ **MySQL 控制表**（唯一协作媒介）→ **sync worker**（独立部署的数据面）→ **前端**（`redis-ops-frontend`，展示）。worker 不依赖 Platform 源码，只通过 `redis-ops-sync-contract`（0.3.2）共享枚举/策略/能力判定，通过 MySQL 表接收指令、回写状态。Worker 不执行 schema migration，升级顺序固定为 Platform 先行（README）。

```
Platform ──写──▶ async_job / sync_task ──轮询──▶ SyncControlWorker（500ms）
                                                      │ claim job
                                                      ▼
                                              NativeSyncCoordinator
                                                      │
                            ┌─────────────────────────┴──────────────────┐
                            ▼                                            ▼
                 StandaloneSyncTaskRunner                     ClusterSyncTaskRunner
                 （源/目标均非 Cluster）                    （每源 master 一 channel）
                            └───────────┬──────────────────────────────┘
                                        ▼
              源端 PSYNC 复制流 ──▶ 加密 spool（磁盘）──▶ 目标端 RESP 会话（fence 保护）
```

## 2. 模块划分

| 模块 | 职责 | 特点 |
|------|------|------|
| `sync-protocol` | RDB 流式解析、RESP 编解码、复制握手、命令规划 | 纯库、零 Spring 依赖、自带单测 |
| `sync-service` | 运行时：runner、控制轮询、spool、预检、持久化 | Spring Boot 3.3 / JDK 21 / MyBatis |
| `redis-ops-sync-contract` | 状态枚举、命令策略/能力、契约版本 | Platform 与 Worker 的共享语言 |

分层干净：协议层可独立测试，运行时层只做编排与生命周期。

## 3. 任务生命周期（控制面）

- **指令通道**：`async_job` 7 类 job_type（PRECHECK/START/RESUME/PAUSE/FINISH/CANCEL/RATE_LIMIT），claim 规则区分全局抢占（START）与 owner 路由（PAUSE/FINISH），失败指数退避 `LEAST(60,POW(2,attempts))` 后转 FAILED（`WorkerControlJobMapper.java:39-44`）。
- **状态机**：15 态（`SyncContractStatus`），`WorkerSyncTransitionPolicy` 白名单约束 + `sync_task.version` 乐观锁写入，终态不可出；CAUGHT_UP 必须携带 rpoSeconds（`WorkerMyBatisSyncRepository.java:98`）。
- **并发治理**：`Semaphore(max-concurrent-tasks=2)` + 单线程 MAX_PRIORITY 租约续期线程（5s 周期）；`NativeSyncRecoveryWorker` 每 2s 扫描租约过期的可恢复任务发起接管。
- **启动副作用**：START 时由 coordinator 对目标逐节点 FLUSHALL/FLUSHDB（`TargetResetter`，每节点记 TARGET_FLUSH 事件）——目标库被清空是设计内行为，靠 precheck 的 DISTINCT_CLUSTERS 防误配。

## 4. 全量同步链路

```
PSYNC ? -1 → FULLRESYNC ─▶ RDB 边收边写加密 spool ─▶ ACK ─▶ 并行解析+多lane RESTORE
                                                                    │
                              whole-RDB checkpoint ◀────────────────┘
                                    │
              replaySpool（重放 RDB 传输期间积压的增量命令）→ PSYNC <replId> <offset> 续传 → applyLive
```

- **握手**：AUTH（密码 char[] 用后清零）→ PING → REPLCONF capa psync2 → PSYNC；支持 diskless（EOF marker）（`ReplicationHandshake.java:17-70`）。
- **RDB 解析**：流式、保留 RDB 原始编码直接拼 DUMP payload（附版本字节+CRC64），不做对象级反序列化；module/pre-GA 编码解析期即 fail-closed（`RdbStreamParser.java:87-88`）。
- **应用侧**：`FullRestorePool` N lane（默认 4）+ 有界队列（2000 条）+ pipeline 100 条/4MB 切批；每批 `WATCH fence → MULTI{N×RESTORE REPLACE ABSTTL + 进度 SET} → EXEC`，失败重连重试 3 次（`FullRestorePool.java:168-182`）。
- **checkpoint 粒度 = 整个 RDB**：全量应用完成才写一次 checkpoint（`StandaloneSyncTaskRunner.java:398-403`），失败即整轮重来——简单且正确（幂等 RESTORE），代价是大任务重跑成本高。

## 5. 增量同步链路

读线程（readLoop）与写线程（applyLive）经 `applyQueue`（10,000 条有界队列）衔接：

1. **读**：半帧防御——SocketTimeout 时已消费字节则转 EOF，绝不推进 offset（`ReplicationCommandReader.java:39-48`）；每条命令带 `[startOffset,endOffset)`。
2. **持久化先行**：`spool.append` 成功后才入队、才 ACK 源端——崩溃后总有完整重放材料。
3. **事务组装**（v3 策略）：Assembler 管边界（MULTI/EXEC 配对，pending 上限 10,000 条/16MB，流内出现 DISCARD/WATCH 即 fail）→ Batcher 管去重与合批（`endOffset <= max(consumed, committed)` 丢弃重叠）→ Planner 管语义裁决（整单元先判：跨库/跨作用域/跨 slot/保留前缀，再逐命令规划）（`SourceTransactionPlanner.java:25-98`）。
4. **应用**：批 100 条/1MB → `applyTarget` 两段式提交（见 §6）→ checkpoint 推进。
5. **多 DB 过滤**：跟踪复制流中的 SELECT，仅 `sourceDb` 的命令落目标（其余 skip），实现多 DB 源 → 单 DB 目的映射。

## 6. 正确性内核（本设计最有价值的部分）

四层机制互相咬合，解决"分布式下双写者与半提交"问题：

| 机制 | 实现 | 防什么 |
|------|------|--------|
| **MySQL 租约 + fencing_generation** | claim 原子 UPDATE，每次 claim 代数 +1（`WorkerSyncMapper.java:45-59`） | 双 worker 同时认为自己是 owner |
| **进程内 LeaseGuard** | 单调时钟，deadline = 租期 − 2000ms 安全边际，revoke 后不可复活 | 长 GC/JVM 暂停后的僵尸写 |
| **Redis fence 键** | `v1\|epoch\|generation\|runtimeId\|workerId`，写事务前 WATCH 校验四元组属主 | 旧 worker 在租约丢失后继续写目标 |
| **两段式 checkpoint** | 先写 `pending-batch-v2:{prev}:{next}` 标记 → 执行 → 确认覆盖；恢复时读到 pending 即 BLOCKED_TARGET_BATCH_UNCONFIRMED，**拒绝自动重放**（`TargetCommandSession.java:383-414`） | "命令可能已执行但不确定"的歧义——宁可人工介入不猜 |

epoch（fullSyncEpoch）隔离不同轮全量；generation 隔离同任务不同接管者。旧 worker 的迟到写会因 fence 属主不符在 EXEC 前被拦下（WATCH 失败 → FencingException → LEASE_LOST 路径）。

**崩溃恢复三级递降**：目标 checkpoint 直接续传（PSYNC continue）→ spool 重放（`forEachCommandAfter(applied)`）→ spool 元数据全量重跑；spool 目录被锁则 takeover 目录降级重放（`StandaloneSyncTaskRunner.java:314-345`）。

## 7. 安全设计

- **静态凭据**：`redis_cluster_secret` 表 AES-256-GCM（IV=密文前 12B，AAD 绑定 secret_uuid），密钥环来自 `REDIS_OPS_CREDENTIAL_KEYS` 环境变量、禁空默认；明文只存 char[] 且 close 时清零（`WorkerCredentialDecryptor.java:24-81`）。
- **落盘数据**：spool 默认明文（`SYNC_ENGINE_SPOOL_ENCRYPTION=NONE`，换取同步热路径吞吐）；可显式配 `AES_GCM` 开启加密——per-task key = HMAC-SHA256(master, "redis-ops/sync-spool/v1"+taskId)，记录级随机 IV + GCM tag，AAD 绑定 taskId+kind+段号（防换段重放）；读取侧长度/CRC/认证三重校验，且按文件本身识别格式，切到 NONE 仍可恢复此前加密写出的 spool。
- **日志纪律**：错误消息截断脱敏入库；fence/checkpoint 解码 fail-closed 不回显原始字节。
- **保留命名空间**：`__redis_ops_sync_*` 前缀在源端 skip、目标端 precheck 扫描冲突 fail-closed。

## 8. 背压与限流（四层）

1. `ReplicationQueueBudget`：Semaphore 32MB 编码字节（仅事务模式），限制队列驻留字节；
2. `applyQueue`：10,000 条有界队列，读线程随之阻塞；
3. spool 水位：≥ limitBytes×90% 时读循环 sleep（`StandaloneSyncTaskRunner.java:459-460`）——最终背压传导到停止 ACK 源端；
4. 应用节流：`throttle()` 按任务级 ops/带宽限额 parkNanos，保护目标端。

含义：worker 永不丢数据，只会变慢——压力最终回传给源端复制积压。

## 9. 可观测性

- **真实 RPO 测量**（巧妙）：向**源端**写心跳 key，该 SET 沿复制流回来时提取时间戳 → 端到端 timestamp lag（`StandaloneSyncTaskRunner.java:770-781`）；追平时退化用 offset 差/吞吐估算，带 `rpoMethod` 与 confidence 字段（`WorkerSyncMetricSnapshot`）。
- 全量进度按 lane 级 stage/bytes/keys 每秒聚合入库（`FullSyncProgressTracker`）；指标、事件流水（sync_task_event）、Prometheus 端点齐备。

## 10. 设计评价

### 亮点

1. **正确性优先的一致取舍贯穿始终**：未知命令 fail-closed、半帧不推进 offset、pending 标记拒绝自动重放、fence 四元组属主校验——所有歧义场景都选择"停下来"而不是"猜着继续"。
2. **平台解耦干净**：数据面零 API 依赖，MySQL 表即契约，worker 可独立发布。
3. **安全默认值高**：静态加密凭据 + 可选落盘 spool 加密（`AES_GCM`，默认 `NONE`）+ 密钥分层派生。
4. **协议层可测性好**：sync-protocol 纯库 + 完整单测（parser/planner/assembler 均有对应 Test）。

### 当前风险与局限（按严重度）

| # | 问题 | 依据 | 影响 |
|---|------|------|------|
| 1 | **目标端会话无读超时**：`TargetCommandSession` 构造只设 connect 超时，不设 `SoTimeout`（56-72 行）；且 socket read 不响应中断，`cancel()` 的 `shutdownNow()` 解除不了阻塞 | 全部目标写路径 | 目标端 TCP 半开/主从切换挂起时，apply 线程**无限期阻塞**，任务既不死也不推进，需重启进程 |
| 2 | **增量路径每批固定往返开销**：两段式 = WATCH+GET×2+MULTI+EXEC（prepare）+WATCH+MULTI+EXEC（apply）+WATCH+MULTI+EXEC（confirm）≈ 3 EXEC/批 | `apply()` 设计 | 正确性代价明确，但小命令密集场景吞吐受限；单目标连接串行（Standalone）放大此问题 |
| 3 | **全量失败整轮重来**：checkpoint 粒度=整个 RDB | §4 | 百 GB 级任务在 99% 处失败也要全重跑（无断点） |
| 4 | **Cluster 拓扑变化即 BLOCKED**（源 reshard、master 增减） | `ClusterSyncTaskRunner:839-856` | 迁移期间源集群 reshard 会中断同步，需人工恢复 |
| 5 | **值路径内存放大**：RDB 值在 worker 堆内约 4 份拷贝 + 队列按条数背压 | `RdbStreamParser:94-102`、`FullRestorePool:70` | 大 key 场景 OOM 风险——即已立项的 big-key-split 方案要解决的问题（后续单独评审） |
| 6 | 预检版本窗口 5.0–8.4、DR 要求版本一致、多 key 需 6.2+ | `SyncPrecheckExecutor` | 老版本目标受限；属产品边界而非缺陷 |

#1 建议独立立项修复（它不是大 key 问题，是当前设计的可用性隐患）；#5 的拆分方案已在 [big-key-split-design.md](big-key-split-design.md) 评审中，其 P0 三项前置修复（字节级背压、去访问器 clone、SoTimeout）恰好同时缓解 #1——后续评审大 key 方案时建议合并考虑。
