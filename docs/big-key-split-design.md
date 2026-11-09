# 大 Key 拆分同步设计方案（全量 RDB 阶段）

> 状态：**P0 已实施（2026-11-09），P1 起待实施**（2026-10-20 初稿，2026-11-09 评审定稿并对齐 f32e271 批量改造后的代码现状；三项待决问题已决，见 §14）
> 范围：sync-worker 全量同步（FULL_SYNCING / RECOVERING_SPOOL）阶段的大 key 处理
> 关联代码：`sync-protocol`（RDB 解析）、`sync-service/runtime`（全量恢复链路）

## 1. 背景与问题

当前全量同步对每个 key 的处理路径是：RDB 编码字节 → DUMP payload → 单条 `RESTORE ... REPLACE ABSTTL`。
该路径对大 key 存在四类问题（按 2026-11 现状重新核对）：

| # | 问题 | 现状（2026-11-09 核对） | 位置 |
|---|------|------|------|
| 1 | ~~队列背压按条数而非字节~~ | **已缓解**：`FullRestoreQueueBudget` 字节信号量已随 f32e271 改造落地（默认 64MB，`@Value` 兜底）。遗留两点：① oversized 单条按 `min(bytes, limit)` 独占全部许可、队列清空后单独放行（语义刻意，注释在案）；② 配置键 `full-apply-queue-max-bytes` **未出现在任何 yml / env example**（`application.yml:65-68` 只有其余四个 full-apply 键） | `FullRestoreQueueBudget.java:7-33`（permits `:22-24`）、`StandaloneSyncTaskRunnerFactory.java:41` |
| 2 | 访问器 clone 造成单 key 内存放大数倍 | **比初稿认知更糟**：`RdbEvent.KeyValue` 构造时 clone + 每次访问器再 clone（`RdbEvent.java:6-19`），`RestoreRequest` 同样双 clone（`TargetCommandSession.java:783-798`）。单 key 全链路 ~9 份拷贝：解析器内 ~6 份瞬时（峰值 3 份并发）、2 份常驻（队列里的最终 payload + 中间传递）、下游 `estimatedBytes`/RESTORE 写出等访问器 4~6 份瞬时 | `RdbEvent.java:6-19`、`TargetCommandSession.java:783-798`、`FullRestorePool.java:147,157,214-215` |
| 3 | 超过 `full-apply-transaction-max-bytes`（默认 4MB）的单 key 仍单条 RESTORE，巨型 MULTI 原子阻塞目标主线程；worker 侧无 SoTimeout，无限期挂起 | **仍成立**（cluster 路径形态已变）：批量上限只切批、不切单条（单条超限时独立成批，`ClusterFullRestorePool.java:107-132`）；cluster 目标已是 per-slot pipelined 事务（两阶段 pipeline，见 §7），但 oversized key 依旧是单条巨型 RESTORE 落在某个 MULTI 里；SoTimeout 仍缺失。**新发现**：standalone 路径 `restoreBatch` 捕获 `RespProtocolException` 重试 3 次，把目标 OOM 掩盖成 "target master remained unavailable"（`FullRestorePool.java:187-201`） | `ClusterFullRestorePool.java:107-132`、`TargetCommandSession.java:363-406`、`FullRestorePool.java:187-201` |
| 4 | 解析阶段整个 value 在堆内物化 | **仍成立**：`readExact` 仍是 `input.readNBytes(length)` 整体物化（`RdbStreamParser.java:284`），无任何大小上限；解析器内单 key ~6 份瞬时拷贝（`readNBytes` → BAOS → `toByteArray` → `writeBytes` → CRC 装配 → 终版 `toByteArray`） | `RdbStreamParser.java:94-102, 260-270, 281-292` |

RESTORE 只接受完整 DUMP 对象，**该路径本质上不可拆分**。本方案为超过阈值的 key 引入一条
元素级重建路径（本文称"拆分路径"），小 key 继续走 RESTORE（快、原子）。

## 2. 目标与非目标

### 目标

1. 单 key 在 worker 堆内的常驻内存从 O(key 大小) 降为 O(batch 大小)；
2. 目标端不再被单条巨型 RESTORE 原子阻塞；
3. 拆分过程对目标读者原子可见（临时 key + RENAME）；
4. 崩溃/重启后可安全重来，不污染真实 key；
5. 与现有 fence / lease / 策略体系集成，不绕过任何安全检查；
6. 结构性无法同步的 key（超 hard-cap）**不拖死整个任务**：跳过 + 告警 + 报告清单（§5.3）。

### 非目标

1. 不拆分 stream（PEL/消费组重建复杂度过高，收益低）；
2. 不拆分 module 类型（维持 fail-closed）;
3. 不做增量阶段的命令级大 key 拆分。注意：增量主循环 `readCommand()` 实际**无界**
   （`SourceReplicationSession.java:57-63`），16MB 上限仅约束 MULTI/EXEC 装配
   （`SourceTransactionAssembler.MAX_BYTES`，超限 `BLOCKED_TRANSACTION_LIMIT` fail-closed）；
   即增量对普通大 value 命令仍会整体物化，对本方案是已知边界而非矛盾；
4. 不保证拆分后目标端内部编码与源一致（编码漂移是固有代价，见 §8）。

## 3. 总体方案

**阈值触发的混合策略**：

```
RDB 解析
  ├─ key payload ≤ full-apply-max-restore-bytes ──→ 现有路径：RestoreRequest → 恢复池 → RESTORE（不变）
  └─ key payload > 阈值 ──→ 按 §5.2 路由表分派：
        ├─ 可拆类型且 ≤ hard-cap ──→ 拆分路径：元素级解码（流式） → UNLINK 临时 key
        │     → 分批元素命令(TEMP key) → MULTI { RENAME temp→real; PEXPIREAT real } → 下一个 key
        └─ string（首版）/ 不可拆类型且 ≤ hard-cap ──→ 回退单条 RESTORE + WARN
        └─ 任何类型 > hard-cap ──→ 跳过该 key + 告警 + 报告清单（§5.3）
```

- 拆分是**极少数 key 的兜底**，默认阈值 64MB；绝大多数 key 仍走 RESTORE。
- 恢复池现状为双池（f32e271 后）：`FullRestorePool`（standalone 目标，per-lane batcher，
  每批新建连接重试）与 `ClusterFullRestorePool`（cluster 目标，per-slot pipelined 事务）。
  拆分 key **绕开两者**：串行处理（单写线程，天然保序），不进共享队列。
- 两个开关同时生效才启用：`full-apply-max-restore-bytes > 0` **且** 任务策略
  `SyncCommandPolicy.allowSafeSplit() == true`（复用现有"可安全拆分"语义，`CommandPlanner.java:56` 同源）。
  任一不满足 → 维持现状（单条 RESTORE），并在 precheck 报告中提示。

## 4. 配置项

新增到 `sync.engine`（`application.yml` / `application-p2.yml` / `deploy/conf/redis-ops.env.example`）：

| 参数 | 环境变量 | 默认值 | 说明 |
|------|----------|--------|------|
| `full-apply-max-restore-bytes` | `SYNC_ENGINE_FULL_APPLY_MAX_RESTORE_BYTES` | `67108864`（64MB） | 单 key 走 RESTORE 的上限；`0` = 禁用拆分，全部走现状路径 |
| `full-apply-split-batch-bytes` | `SYNC_ENGINE_FULL_APPLY_SPLIT_BATCH_BYTES` | 同 `full-apply-transaction-max-bytes` | 拆分路径单批字节数上限 |
| `full-apply-split-batch-commands` | `SYNC_ENGINE_FULL_APPLY_SPLIT_BATCH_COMMANDS` | `512` | 拆分路径单批命令数上限 |
| `full-apply-split-hard-cap-bytes` | `SYNC_ENGINE_FULL_APPLY_SPLIT_HARD_CAP_BYTES` | `503316480`（480MB） | 拆分/RESTORE 的总兜底上限：超过即跳过+告警（§5.3）。**必须小于目标端 `proto-max-bulk-len`**（默认 512MB），precheck 校验（§5.2 不变式） |
| `full-apply-unsupported-type-policy` | `SYNC_ENGINE_FULL_APPLY_UNSUPPORTED_TYPE_POLICY` | `FAIL` | 解析级不支持类型（module 等）的处理：`FAIL` / `SKIP`（见 §5.3） |
| `full-apply-socket-timeout-ms` | `SYNC_ENGINE_FULL_APPLY_SOCKET_TIMEOUT_MS` | `300000`（5 分钟） | 目标连接 SoTimeout（P0-3，覆盖所有恢复连接） |

同时补录存量缺口：`full-apply-queue-max-bytes`（已实现但缺文档）写入 yml 注释与 env example。

## 5. 解析侧设计（sync-protocol）

### 5.1 事件模型扩展

`RdbEvent`（sealed interface）新增三个变体，仅在 split 模式下产生：

```java
// RdbEvent.java 新增
record BigKeyValueBegin(int database, byte[] key, long expireAt,
                        int rdbType, long estimatedElements) implements RdbEvent {}
record KeyElement(byte[] member, byte[] value, double score) implements RdbEvent {}   // 按 rdbType 语义化填充
record BigKeyValueEnd(byte[] key, long payloadBytes, long elementCount) implements RdbEvent {}
```

- `parse(Consumer<RdbEvent>)` 保持现有签名与行为（不破坏增量恢复链路）；
- 新增 `parse(Consumer<RdbEvent>, SplitOptions options)`：`options.splittableTypes()` 命中的类型走元素流，其余仍发完整 `KeyValue`；
- 关键约束：split 模式下元素**边读边发**，解析器不持有元素引用（复用单个读写缓冲），避免重新引入整体物化；
- 类型与长度在读取 value 之前已知（`RdbStreamParser` 类型常量 `:12-19`、`skipObject` 结构遍历 `:104-156`），
  阈值判定发生在物化**之前**——这是拆分路径能把大 key 内存降到 O(batch) 的前提。

### 5.2 类型支持矩阵与路由

| RDB 类型 | 编码 | 拆分方式 | 备注 |
|----------|------|----------|------|
| `TYPE_STRING`(0) | — | **首版不拆**（§14-3 已决，SETRANGE 路径推迟 P1.5）；超阈值回退 RESTORE | string 大 key 场景少，且 SETRANGE 分片期间目标持稀疏 SDS |
| `TYPE_LIST`(1) / `TYPE_LIST_QUICKLIST`(14) / `TYPE_LIST_QUICKLIST_2`(18) | listpack/ziplist 节点 | `RPUSH` 批量 | 需解码 quicklist 节点 blob |
| `TYPE_SET`(2) | hashtable | `SADD` 批量 | |
| `TYPE_SET_INTSET`(11) | intset | `SADD` 批量 | 需解码 intset blob |
| `TYPE_ZSET`(3) / `TYPE_ZSET_2`(5) | hashtable+skiplist | `ZADD` 批量 | score 用 Java `Double.toString`（最短往返表示，bit 精确） |
| `TYPE_ZSET_ZIPLIST`(12) / `TYPE_ZSET_LISTPACK`(17) | 压缩编码 | `ZADD` 批量 | 需解码 blob |
| `TYPE_HASH`(4) | hashtable | `HSET` 批量 | |
| `TYPE_HASH_ZIPMAP`(9) / `TYPE_HASH_ZIPLIST`(13) / `TYPE_HASH_LISTPACK`(16) | 压缩编码 | `HSET` 批量 | 需解码 blob |
| `TYPE_STREAM_*`(15/19/21) | — | **不支持拆分** | PEL/消费组无法用普通命令重建 |
| `TYPE_HASH_METADATA(24)` / `TYPE_HASH_LISTPACK_EX(25)`（字段级 TTL，Redis 7.4） | — | **不支持拆分** | 字段级 TTL 无法经 HSET 重建 |
| `TYPE_MODULE(6/7)`、pre-GA 编码 | — | **不支持** | 维持现有 fail-closed（解析期抛 `UnsupportedRdbTypeException`，与大小无关） |

**路由表**（"是否可拆"与"是否超阈值"是正交维度；2026-11-09 定稿：超 hard-cap 一律**跳过+告警+清单**，不再任务失败）：

| | ≤ 阈值 | > 阈值 且 ≤ hard-cap | > hard-cap |
|---|---|---|---|
| 可拆类型（list/set/zset/hash） | RESTORE（现状） | **拆分路径** | **跳过 + 告警 + 清单**（§5.3） |
| string（首版不拆） | RESTORE | 回退 RESTORE + WARN | 跳过 + 告警 + 清单 |
| 不可拆类型（stream / 字段级 TTL hash） | RESTORE | 回退 RESTORE + WARN | 跳过 + 告警 + 清单 |

不可拆类型超阈值时**不** fail-closed：单条 RESTORE 在 `proto-max-bulk-len`（默认 512MB）内本就可行；
只有超过 hard-cap（任何路径都必然失败）才跳过——跳过有显式告警与清单兜底（§5.3），不是静默缺数据。

**单元素超限不变式**：单条元素（如 hash 某 field 的 value）≤ 整 key payload。只要
`hard-cap < proto-max-bulk-len` 成立（默认 480 < 512），则 ≤hard-cap 的 key 其任何元素都必然可写，
"key 可拆但元素不可写"的死角不存在。该不变式由 **precheck 校验目标 `proto-max-bulk-len` > hard-cap**
保证（`CONFIG GET proto-max-bulk-len`，取不到按默认 512MB）；若运维误配（hard-cap ≥ 实际上限），
解析侧在元素级再设一道防御判定（元素 > 目标 bulk 上限 → 该 key 终止拆分、UNLINK 临时 key、转跳过+告警），
避免依赖单一配置校验。

新增 `RdbValueDecoder`（包私有，`sync-protocol`）：负责 ziplist / listpack / intset / zipmap
blob 的元素解码。解码失败（含恶意/损坏数据）→ 该 key 回退 RESTORE；payload 仍超 hard-cap → 跳过+告警，
跳过前 `UNLINK` 已写入的临时 key。

### 5.3 跳过策略（2026-11-09 定稿扩展）

跳过的适用面（两类）：

1. **超 hard-cap 的任何类型 key**（§5.2 路由表最右列）——结构性不可同步；
2. **解析级不支持类型**（module 等，见下表）——与大小无关。

| 情况 | 是否可"跳过" | 默认策略 |
|------|--------------|----------|
| 超 hard-cap（任何类型）/ 单元素超 bulk 上限（防御兜底） | 适用 | **跳过 + 告警 + 清单**（本节统一语义） |
| 可同步但不可拆（stream / 字段级 TTL hash） | 不适用——RESTORE 完整支持，走 §5.2 路由表 | 回退 RESTORE |
| 老 MODULE（type 6）：模块自定义 IO 序列化，**无长度前缀** | **技术上不可能**——不实现模块编解码器就无法定位下一个 key 边界 | fail-closed（现状，不可配置） |
| MODULE_2（type 7）：payload 为长度前缀字符串 | 技术上可行（读 blob 丢弃；实现阶段用真实 module 数据验证边界） | 默认 fail-closed，可经 `full-apply-unsupported-type-policy: SKIP` 显式 opt-in |

**跳过三件套**（禁止静默跳过——跳过 = 目标缺数据，必须让用户看见）：

1. WARN 日志：key 字节长度 + SHA-256 前 8 字节，**不打 key 内容**（防敏感数据泄露）；
2. 计数：`sync_skipped_oversize_keys_total`（超限）/ `sync_skipped_unsupported_keys_total`（类型）；
3. **任务报告跳过清单**：每跳过一个 key 记入任务级报告（key 指纹、类型、payloadBytes、原因），
   FINISH/结算时供核对；
4. **平台侧告警**：复用现有 `LARGE_KEY_FOUND` ruleType 触发（先例：`RiskScanService.java:329` 调
   `AlertService.trigger`，`AlertService.java:47-60`；无需 DDL 变更）。worker 侧经引擎事件/任务报告通道
   上报，平台侧接线归 P3。

**前置发现（P3，precheck 增强）**：precheck 阶段对源库 `SCAN` 采样 key 类型与 `MEMORY USAGE`，
发现 module / 不支持编码 / 超 hard-cap 的 key 时在预检报告中直接提示（含数量与类型名），让用户在
**任务创建时**决策（清理/拆分源 key 后再建任务），而不是同步跑到一半才跳过——这是比"跳过"更根本的改进。

## 6. 临时 key 设计

### 6.1 命名

```
Cluster：  "{" + <原key> + "}" + ":__ropstmppart__:" + <taskId> + ":" + <channel>
Standalone：__ropstmppart__:{<taskId>}:<channel>:<原key 的 SHA-256 hex 前 16 字节>
```

设计要点：

1. **同 slot 保证**（Cluster 路径）：Redis hash-tag 取第一个 `{` 到第一个 `}` 之间的文本。
   用原 key 全文作为 tag（`{<原key>}...`），无论原 key 是否自带 tag，临时 key 必与原 key 同 slot，
   `RENAME` 不会产生 `CROSSSLOT`。`ClusterTargetRouter.restore` 按 `RedisSlot.of(request.key())` 路由
   （`ClusterTargetRouter.java:89-109`），临时 key 天然路由到同一连接。
2. **确定性命名**：同一真实 key 的临时 key 名固定。每次拆分开始先 `UNLINK` 临时 key，
   因此崩溃重跑会先清掉上一轮残留，无需全局清理扫描。
3. **保留命名空间冲突**：`TargetCommandSession.assertReservedNamespaceAvailable`
   （`TargetCommandSession.java:183`）会扫描 `__redis_ops_sync_*` 并 fail-closed。因此临时 key
   **刻意避开**该前缀，使用独立前缀 `__ropstmppart__`，不触碰现有检查；`full_progress` 等既有键不受影响。

### 6.2 可见性与 TTL

1. 拆分期间读者看到的是**旧的真实 key**，临时 key 对业务不可见（前缀私有）；
2. 收尾在一个 MULTI 内完成，原子替换：

```
WATCH fence → 校验 fence → MULTI
    RENAME  <tempKey> <realKey>          # O(1) 指针交换，原子覆盖旧值
    PEXPIREAT <realKey> <expireAt>       # 仅当源 key 有 TTL；RENAME 会继承 temp 的"无 TTL"，必须显式补
EXEC
```

3. 拆分期间目标端真实 key 与临时 key 并存，**目标内存瞬时最多多出一份大 key**，
   precheck 报告需提示该风险。

## 7. 下发侧设计（sync-service/runtime）

### 7.1 组件

```
StandaloneSyncTaskRunner / ClusterSyncTaskRunner 的 RDB 解析回调
  └─ payload > 阈值 且 allowSafeSplit → SplitKeyRestoreWriter（每 channel 单线程，串行）
       ├─ TargetCommandSession.applySplitBatch(...)   # 新增，复用 WATCH fence 事务模式
       └─ 进度上报 FullSyncProgressTracker（新增 SPLITTING_KEY 阶段，聚合上报锚点 :77-82）
```

- **串行写线程**：拆分 key 的元素批次必须严格有序，不能进恢复池的多 lane 共享队列（lane 间无顺序保证：
  `ClusterFullRestorePool.java:99-152` per-lane batcher）。拆分 key 本身按定义稀少，串行不影响整体吞吐。
- `applySplitBatch` 的两个参照（按目标形态择一）：
  - standalone 目标：`TargetCommandSession.restoreBatch`（`:319-349`，WATCH fence → MULTI → N 命令 →
    progress SET → EXEC）；
  - cluster 目标：`TargetCommandSession.restoreNodeBatch`（`:363-406`，两阶段 pipelined：先全部
    WATCH+GET，再全部 MULTI+N 命令+EXEC；**事务内无 progress SET**，进度走 MySQL 侧
    `FullSyncProgressTracker`）。
- **不得复用增量路径的 `apply()`**（现签名 `TargetCheckpoint apply(List<CommandPlan.PlannedCommand>, TargetCheckpoint, ...)`，
  destructive 检查在 `:524` 经 `SyncCommandCapabilities.destructive`）：其会拦截 `UNLINK`，且
  prepare/confirm 两段式 checkpoint 语义不适用于拆分场景。
- 新命令白名单（拆分路径专用）：`RPUSH / SADD / ZADD / HSET / RENAME / PEXPIREAT / UNLINK`（string 落地时
  追加 `SETRANGE / SETRANGE`首片所需 `SET`），需在 `SyncCommandCapabilities` 中登记为拆分路径允许集，与增量
  命令策略分类解耦。**发布注意**：`SyncCommandCapabilities` 位于 `redis-ops-sync-contract` 模块
  （`io.github.susongyan.redisops.sync.contract`，platform 以二进制依赖引用），改动需 contract 先发版、
  platform 后升级（部署顺序见 `../CONTRACT.md` 契约 1）。

### 7.2 分批策略

- 批次边界：`batchBytes + next > full-apply-split-batch-bytes` **或** `batchCommands` 达上限即切批；
  单条元素超过批字节上限时独立成批（与 `ClusterFullRestorePool.java:107-132` 语义一致）；
- **元素级防御判定**（§5.2 不变式的兜底）：元素字节 > 目标 bulk 上限（precheck 探测值）→ 该 key
  终止拆分 → `UNLINK` 临时 key → 转跳过+告警；
- 收尾（RENAME + PEXPIREAT）必须是独立批次，绝不与元素批次混排——保证"要么完整旧值、要么完整新值"；
- 每批开始前 `leaseGuard.assertValid()`，批次间隔响应暂停/取消。

### 7.3 失败与恢复

| 场景 | 行为 |
|------|------|
| 元素批次连接类失败 | 重连重试 3 次（沿用 `FullRestorePool.restoreBatch` 模式，`:187-201`）；仍失败 → `UNLINK` 临时 key → 任务失败（全量从 RDB 头重来） |
| list 批次重试的二义性 | `RPUSH` 批**非幂等**：EXEC 已落但回包丢失时盲目重试会重复追加（hash/set/zset 元素级幂等无此问题）。批是原子事务，重连后先 `LLEN tempKey` 对账：等于已确认前缀 → 本批未落，安全重试；等于前缀+本批条数 → 已落，跳过继续。二值判定，无部分落盘态 |
| 元素批次目标 OOM（`OOM command not allowed`） | **不重试**（重试必然再 OOM，且现状会把 OOM 掩盖成连接错误）——立即 `UNLINK` 临时 key → 任务失败，错误信息原样透出。实施时须在重试入口按错误类型分流，修正 `FullRestorePool.java:187-201` 的掩盖问题 |
| 解码失败（压缩编码 blob） | 回退单条 RESTORE（≤hard-cap）；>hard-cap → 跳过+告警 |
| 进程崩溃/被接管 | 真实 key 未被触碰；新 runtime 全量重跑，拆分 key 首批前 `UNLINK` 清残留 |
| fence 被更高 generation 抢占 | `WATCH` 失败抛 `FencingException`，正常失败路径 |
| lease 过期 | 任务转重新认领、全量重跑（非终态失败） |
| 暂停/取消 | 在批次边界生效；取消时 `UNLINK` 已创建的临时 key（writer 持有当前 key 引用） |

checkpoint 粒度维持"整个 RDB"不变，拆分 key 可安全重写。

### 7.4 超时

`TargetCommandSession` 构造时统一 `socket.setSoTimeout(...)`（`full-apply-socket-timeout-ms`，
默认 300000 = 5 分钟）。`requireMultiKeyVersion` 中临时改超时的逻辑不变（`:100-125` 现状）。
同时将单批内的命令字节总和写入执行日志，便于定位目标端慢命令。

## 8. 编码漂移（需在平台侧明示用户）

元素级重建不保留源端内部编码，目标端编码由目标配置与数据量重新决定：

- intset/listpack/ziplist 小编码 key：拆分后必然转为 hashtable/skiplist 等大编码；
- 影响：`OBJECT ENCODING` 输出不同、内存占用不同；功能语义等价。

缓解：**编码走 RESTORE 的 key 不受影响**；只有超过 64MB 的 key 才可能漂移，此类 key 本身
在源端也几乎必然是大编码。该结论需同步到平台文档与 precheck 提示。

## 9. 可观测性

| 项 | 说明 |
|----|------|
| `FullSyncProgressTracker` 新阶段 | `SPLITTING_KEY`（含当前 key 的字节数、已写入元素数/预估元素数），保持每秒聚合上报节奏（`FullSyncProgressTracker.java:77-82`） |
| Micrometer 计数 | `sync_split_keys_total`、`sync_split_elements_total`、`sync_split_batches_total`、`sync_split_fallback_total`（解码失败回退 RESTORE 次数）、**`sync_skipped_oversize_keys_total` / `sync_skipped_unsupported_keys_total`（跳过）** |
| 跳过清单 | 任务报告内嵌跳过明细（key 指纹、类型、payloadBytes、原因），FINISH 时可见；平台侧触发 `LARGE_KEY_FOUND` 告警（§5.3） |
| 日志 | 拆分开始/结束、跳过、回退各 WARN 级一条：taskId、key 字节长度、key SHA-256 前 8 字节、rdbType、元素数。**不打 key 内容**（防敏感数据泄露） |
| 指标落库 | `WorkerSyncFullProgress` 透传 split 阶段信息，Platform 侧展示沿用现有进度通道 |

## 10. 前置修复（P0，本方案的前置依赖）

| # | 修复 | 位置 | 状态 |
|---|------|------|------|
| 1 | 恢复池字节级背压：`Semaphore(fullApplyQueueMaxBytes)`，submit acquire / apply 后 release | `FullRestoreQueueBudget.java:7-33`、`FullRestorePool.java:86-106`、`ClusterFullRestorePool.java:54-73` | ✅ **已随 f32e271 落地**；遗留两项（配置键补录、oversized 独占许可单测）✅ **已于 2026-11-09 随 P0 补齐**（yml×2 + env example + `FullRestoreQueueBudgetTest`） |
| 2 | clone 消除（扩围）：`RdbEvent.KeyValue` **与** `RestoreRequest` 均改为"构造时 clone 一次（或解析侧直出唯一副本）、访问器直读"；`estimatedBytes` 改读字段长度 | `RdbEvent.java:6-19`、`TargetCommandSession.java:783-798`、`FullRestorePool.java:147,157,214-215`、`ClusterFullRestorePool.java:114,124,173-175` | ✅ **已实施（2026-11-09）**：`RdbEvent` 事件改为解析侧直出唯一副本（`readString` 各路径均新分配，防御性 clone 全删，`RdbEventTest` 钉契约）；`RestoreRequest` 构造时 clone 一次 + 访问器直读 + `estimatedBytes()` 收口三处同式公式（`RestoreRequestTest` 钉契约） |
| 3 | 目标连接 `SoTimeout`（§7.4） | `TargetCommandSession` 构造处 | ✅ **已实施（2026-11-09）**：`sync.engine.full-apply-socket-timeout-ms`（默认 300000，<1000ms 拒绝启动）经 Factory/PrecheckExecutor 穿透全部构造链（两 Runner、双恢复池、ClusterTargetRouter、precheck 8 处会话）；`requireMultiKeyVersion` 的临时改超时逻辑未动 |

P0 剩余两项不修，拆分路径自身会被同样的问题拖垮（元素批次同样过队列、同样涉及 payload 拷贝、同样可能被挂起的连接卡死）。

## 11. 风险与边界

| 风险 | 等级 | 缓解 |
|------|------|------|
| 拆分期间目标内存双倍（真实 key + 临时 key） | 中 | precheck 提示；阈值默认 64MB 起步 |
| **跳过策略导致目标静默缺数据** | 中 | 三件套兜底：WARN 日志 + `LARGE_KEY_FOUND` 告警 + 任务报告清单；FINISH 前核对清单（P3 报告展示） |
| 巨型 string 首版不拆：>阈值 ≤hard-cap 的 string 仍走单条 RESTORE，目标主线程阻塞与堆放大依旧 | 中 | 文档明示；P1.5 落地 SETRANGE 后消除；用户可用风险扫描提前发现 string 大 key |
| hard-cap ≥ 目标 `proto-max-bulk-len` 的误配 | 低 | precheck `CONFIG GET` 校验 + §7.2 元素级防御判定双重兜底 |
| 解码器 bug 导致数据不一致 | 高 | 解码失败一律回退 RESTORE；拆分完成后对抽样 key 做 `DEBUG DIGEST` 对比（仅测试环境启用） |
| hash tag 使临时 key 名最长约 2×key 长度 | 低 | 相对 payload 可忽略；key 名 > 16MB 直接 fail-closed |
| `allowSafeSplit` 关闭的存量任务行为变化 | 低 | 无变化：开关不满足时维持现状路径 |
| 目标为旧版本 Redis 不支持 ABSTTL 等 | 低 | 拆分命令集均为 Redis 2.6+ 命令，不引入新版本依赖 |
| 增量阶段边界：普通大 value 命令无界物化、MULTI 装配超 16MB fail-closed | 已知边界 | 非本方案范围（非目标 3）；大 key 在源端被改写引发的增量压力由租约/失败语义兜底，后续另立项 |

## 12. 测试要点

1. **单元**：`RdbValueDecoder` 对 ziplist/listpack/intset/zipmap 的往返解码；临时 key 命名与
   `RedisSlot` 一致性（原 key 自带/不自带 tag、二进制 key）；批次切分边界；`Double.toString` 分数往返；
   `FullRestoreQueueBudget` oversized 独占许可语义（补 P0 遗留）。
2. **集成**（真实 Redis，沿用现有 env-gated 测试模式：`ClusterRestoreNodeBatchRedisTest` 以
   `SYNC_BATCH_TEST_CLUSTER_SLOT0` 门控、`ClusterSyncTaskRunnerIntegrationTest` 以
   `REDIS_SYNC_CLUSTER_IT` 门控）：
   - 1 亿字段 hash、1GB list 的全量拆分同步，`DEBUG DIGEST` 比对；string 场景验证回退 RESTORE 行为
     （P1.5 前不覆盖 SETRANGE）；
   - 带 TTL / 无 TTL / 已过期 key 三种情况收尾正确性；
   - 超 hard-cap key → 跳过 + 清单记录 + 临时 key 无残留 + 任务不失败；
   - 元素超 bulk 上限（模拟误配）→ 终止拆分转跳过；
   - 目标 OOM 注入 → 不重试、错误原样透出、临时 key 已 UNLINK；
   - 拆分中途 kill worker → 重启后全量重跑 → 数据一致、无临时 key 残留；
   - list 拆分中注入"EXEC 已落但断连接"（写后杀连接）→ `LLEN` 对账后无重复元素；
   - fence 被抢占、lease 过期、目标主从切换三个故障注入；
   - stream / module / 字段级 TTL hash → 按路由表行为（回退或 fail-closed）且告警/清单正确。
3. **回归**：阈值 = 0 时全量同步行为与改动前逐字节一致（现有测试全绿）。
4. **覆盖**：新增类 80%+ 行覆盖（JaCoCo），`RdbValueDecoder` 与 `SplitKeyRestoreWriter` 优先。

## 13. 实施计划

| 阶段 | 内容 | 交付判据 |
|------|------|----------|
| P0 ✅（2026-11-09，`mvn verify` 全绿） | 前置修复剩余两项（clone 消除、SoTimeout）+ 背压遗留（配置键补录、`FullRestoreQueueBudget` 单测） | 现有测试全绿 + 新增背压/克隆单测 |
| P1 | 解析器元素级路径（§5）+ `RdbValueDecoder` + Standalone 拆分链路（§6.1 Standalone 命名、§7）+ 跳过三件套（§5.3） | Standalone 集成测试通过（含超 hard-cap 跳过路径） |
| P1.5（可选） | string 的 SETRANGE 拆分路径（§14-3 决议推迟至此） | 500MB string 拆分 + `DEBUG DIGEST` 比对 |
| P2 | Cluster 拆分链路（hash-tag 路由、`ClusterTargetRouter` 适配） | Cluster 集成测试通过 |
| P3 | 可观测性收尾（§9 指标/报告展示）+ precheck 采样扫描（大 key `MEMORY USAGE`、不支持类型 `SCAN ... TYPE`、`proto-max-bulk-len` 校验）+ 平台侧 `LARGE_KEY_FOUND` 告警接线 | 平台侧可见拆分指标、跳过清单与类型风险提示 |

每阶段独立合入，P0 可先行发布（独立价值：单 key 堆占用从 ~9 份降到 ~2 份、消除连接挂起风险）。

## 14. 待决问题（2026-11-09 全部已决）

1. ~~超过 hard-cap 或其他无法处理的 key，是否允许配置为"跳过该 key + 告警"而非任务失败~~
   **已决：跳过 + 告警 + 报告清单**（§5.3 三件套）。理由：大 key 是个别数据的结构问题，
   不应拖死整库同步任务；缺数据风险由告警与 FINISH 前清单核对兜底。
2. ~~`full-apply-split-hard-cap-bytes` 是否需要小于 480MB~~
   **已决：维持 480MB 默认、可配置**；正确性由 precheck 校验 `hard-cap < 目标 proto-max-bulk-len`
   保证（目标若调小过 `proto-max-bulk-len`，用户须同步调小 hard-cap）。
3. ~~巨型 string 的 SETRANGE 路径是否首版就做~~
   **已决：首版不做**，string 超阈值回退 RESTORE（与现状一致），SETRANGE 落地排在 P1.5。
