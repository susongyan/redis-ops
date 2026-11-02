# 大 Key 拆分同步设计方案（全量 RDB 阶段）

> 状态：设计评审中
> 范围：sync-worker 全量同步（FULL_SYNCING / RECOVERING_SPOOL）阶段的大 key 处理
> 关联代码：`sync-protocol`（RDB 解析）、`sync-service/runtime`（全量恢复链路）

## 1. 背景与问题

当前全量同步对每个 key 的处理路径是：RDB 编码字节 → DUMP payload → 单条 `RESTORE ... REPLACE ABSTTL`。
该路径对大 key 存在四类问题：

| # | 问题 | 位置 |
|---|------|------|
| 1 | 队列背压按**条数**（2000 条）而非字节，队列中最坏可钉住 2000 个大 key 的 payload → OOM | `FullRestorePool.java:70`（`ArrayBlockingQueue`） |
| 2 | `RestoreRequest` 访问器 clone + `estimatedBytes` 反复复制整个 payload，单 key 瞬时内存放大数倍 | `TargetCommandSession.java:594-609`、`FullRestorePool.java:195-197` |
| 3 | 超过 `full-apply-transaction-max-bytes`（默认 4MB）的单 key 仍单条 RESTORE，一个 MULTI/EXEC 携带整个 payload，目标主线程被原子阻塞；worker 侧无 `SoTimeout`，无限期挂起 | `FullRestorePool.java:143`、`TargetCommandSession.java:56-72` |
| 4 | 解析阶段整个 value 在堆内物化，峰值约为 key 字节数的 4 倍 | `RdbStreamParser.java:94-102,260-270` |

RESTORE 只接受完整 DUMP 对象，**该路径本质上不可拆分**。本方案为超过阈值的 key 引入一条
元素级重建路径（本文称"拆分路径"），小 key 继续走 RESTORE（快、原子）。

## 2. 目标与非目标

### 目标

1. 单 key 在 worker 堆内的常驻内存从 O(key 大小) 降为 O(batch 大小)；
2. 目标端不再被单条巨型 RESTORE 原子阻塞；
3. 拆分过程对目标读者原子可见（临时 key + RENAME）；
4. 崩溃/重启后可安全重来，不污染真实 key；
5. 与现有 fence / lease / 策略体系集成，不绕过任何安全检查。

### 非目标

1. 不拆分 stream（PEL/消费组重建复杂度过高，收益低）；
2. 不拆分 module 类型（维持 fail-closed）;
3. 不做增量阶段的命令级大 key 拆分（增量是命令重放，天然是元素级的）；
4. 不保证拆分后目标端内部编码与源一致（编码漂移是固有代价，见 §8）。

## 3. 总体方案

**阈值触发的混合策略**：

```
RDB 解析
  ├─ key payload ≤ full-apply-max-restore-bytes ──→ 现有路径：RestoreRequest → FullRestorePool → RESTORE（不变）
  └─ key payload > 阈值 且类型可拆 ──────────────→ 拆分路径：
        元素级解码（流式） → UNLINK 临时 key → 分批元素命令(TEMP key)
        → MULTI { RENAME temp→real; PEXPIREAT real } → 继续下一个 key
```

- 拆分是**极少数 key 的兜底**，默认阈值 64MB；绝大多数 key 仍走 RESTORE。
- 拆分 key 串行处理（单写线程，天然保序），不进 `FullRestorePool` 的共享队列。
- 两个开关同时生效才启用：`full-apply-max-restore-bytes > 0` **且** 任务策略
  `SyncCommandPolicy.allowSafeSplit() == true`（复用现有"可安全拆分"语义，`CommandPlanner.java:56-66` 同源）。
  任一不满足 → 维持现状（单条 RESTORE），并在 precheck 报告中提示。

## 4. 配置项

新增到 `sync.engine`（`application.yml` / `application-p2.yml` / `deploy/config/application-pro.yml.example`）：

| 参数 | 环境变量 | 默认值 | 说明 |
|------|----------|--------|------|
| `full-apply-max-restore-bytes` | `SYNC_ENGINE_FULL_APPLY_MAX_RESTORE_BYTES` | `67108864`（64MB） | 单 key 走 RESTORE 的上限；`0` = 禁用拆分，全部走现状路径 |
| `full-apply-split-batch-bytes` | `SYNC_ENGINE_FULL_APPLY_SPLIT_BATCH_BYTES` | 同 `full-apply-transaction-max-bytes` | 拆分路径单批字节数上限 |
| `full-apply-split-batch-commands` | `SYNC_ENGINE_FULL_APPLY_SPLIT_BATCH_COMMANDS` | `512` | 拆分路径单批命令数上限 |
| `full-apply-split-hard-cap-bytes` | `SYNC_ENGINE_FULL_APPLY_SPLIT_HARD_CAP_BYTES` | `503316480`（480MB） | 拆分下限兜底：超过即 fail-closed（目标 `proto-max-bulk-len` 默认 512MB） |
| `full-apply-unsupported-type-policy` | `SYNC_ENGINE_FULL_APPLY_UNSUPPORTED_TYPE_POLICY` | `FAIL` | 解析级不支持类型（module 等）的处理：`FAIL` / `SKIP`（见 §5.3，SKIP 仅对技术可行类型生效） |

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
- 关键约束：split 模式下元素**边读边发**，解析器不持有元素引用（复用单个读写缓冲），避免重新引入 4P 物化。

### 5.2 类型支持矩阵

| RDB 类型 | 编码 | 拆分方式 | 备注 |
|----------|------|----------|------|
| `TYPE_STRING`(0) | — | `SETRANGE` 分片（64MB/片） | 分片顺序写入 |
| `TYPE_LIST`(1) / `TYPE_LIST_QUICKLIST`(14) / `TYPE_LIST_QUICKLIST_2`(18) | listpack/ziplist 节点 | `RPUSH` 批量 | 需解码 quicklist 节点 blob |
| `TYPE_SET`(2) | hashtable | `SADD` 批量 | |
| `TYPE_SET_INTSET`(11) | intset | `SADD` 批量 | 需解码 intset blob |
| `TYPE_ZSET`(3) / `TYPE_ZSET_2`(5) | hashtable+skiplist | `ZADD` 批量 | score 用 Java `Double.toString`（最短往返表示，bit 精确） |
| `TYPE_ZSET_ZIPLIST`(12) / `TYPE_ZSET_LISTPACK`(17) | 压缩编码 | `ZADD` 批量 | 需解码 blob |
| `TYPE_HASH`(4) | hashtable | `HSET` 批量 | |
| `TYPE_HASH_ZIPMAP`(9) / `TYPE_HASH_ZIPLIST`(13) / `TYPE_HASH_LISTPACK`(16) | 压缩编码 | `HSET` 批量 | 需解码 blob |
| `TYPE_STREAM_*`(15/19/21) | — | **不支持拆分** | PEL/消费组无法用普通命令重建，见下方路由规则 |
| `TYPE_HASH_METADATA(24)` / `TYPE_HASH_LISTPACK_EX(25)`（字段级 TTL，Redis 7.4） | — | **不支持拆分** | 见下方路由规则 |
| `TYPE_MODULE(6/7)`、pre-GA 编码 | — | **不支持** | 维持现有 fail-closed（解析期抛 `UnsupportedRdbTypeException`，与大小无关） |

**不可拆类型的路由规则**（"是否可拆"与"是否超阈值"是正交维度）：

| | ≤ 阈值 | > 阈值 且 ≤ hard-cap | > hard-cap |
|---|---|---|---|
| 可拆类型（hash/list/set/zset/string） | RESTORE（现状） | 拆分路径 | 任务失败 `BLOCKED_UNSUPPORTED_SPLIT_KEY` |
| 不可拆类型（stream / 字段级 TTL hash） | RESTORE（现状） | **回退 RESTORE**（单条大 RESTORE，行为与现状一致，日志 WARN 提示） | 任务失败 |

不可拆类型超阈值时**不** fail-closed：单条 RESTORE 在 `proto-max-bulk-len`（默认 512MB）内本就可行，
fail-closed 反而是行为退化；只有超过 hard-cap（连 RESTORE 都必然失败）才任务失败。

新增 `RdbValueDecoder`（包私有，`sync-protocol`）：负责 ziplist / listpack / intset / zipmap
blob 的元素解码。解码失败（含恶意/损坏数据）→ 该 key 回退 RESTORE；payload 仍超
hard-cap → 任务失败，错误码 `BLOCKED_UNSUPPORTED_SPLIT_KEY`，失败前 `UNLINK` 已写入的临时 key。

### 5.3 解析级不支持类型的策略（module / pre-GA 编码）

"不支持"分三种情况，策略不同：

| 情况 | 是否可"跳过" | 默认策略 |
|------|--------------|----------|
| 可同步但不可拆（stream / 字段级 TTL hash） | 不适用——RESTORE 完整支持，与跳过无关 | 见 §5.2 路由表 |
| 老 MODULE（type 6）：模块自定义 IO 序列化，**无长度前缀** | **技术上不可能**——不实现模块编解码器就无法定位下一个 key 边界 | fail-closed（现状） |
| MODULE_2（type 7）：payload 为长度前缀字符串 | 技术上可行（读 blob 丢弃；实现阶段用真实 module 数据验证边界） | 默认 fail-closed，可显式 opt-in 跳过 |

**跳过策略（opt-in）**：新增配置 `full-apply-unsupported-type-policy: FAIL`（默认）/ `SKIP`：

- `SKIP` 仅对技术可行的类型生效（type 6 无论如何都 fail-closed）；
- 每跳过一个 key：WARN 日志（key 字节长度 + SHA-256 前 8 字节，**不打 key 内容**）、
  `sync_skipped_unsupported_keys_total` 计数、跳过 key 清单写入任务报告供事后核对；
- **禁止静默跳过**：跳过 = 目标缺数据，必须让用户在任务报告中看到完整清单。

**前置发现（P3，precheck 增强）**：precheck 阶段对源库 `SCAN` 采样 key 类型，发现
module / 不支持编码时在预检报告中直接提示（含数量与类型名），让用户在**任务创建时**决策，
而不是同步跑到一半才失败——这是比"跳过"更根本的改进。

## 6. 临时 key 设计

### 6.1 命名

```
Cluster：  "{" + <原key> + "}" + ":__ropstmppart__:" + <taskId> + ":" + <channel>
Standalone：__ropstmppart__:{<taskId>}:<channel>:<原key 的 SHA-256 hex 前 16 字节>
```

设计要点：

1. **同 slot 保证**（Cluster 路径）：Redis hash-tag 取第一个 `{` 到第一个 `}` 之间的文本。
   用原 key 全文作为 tag（`{<原key>}...`），无论原 key 是否自带 tag，临时 key 必与原 key 同 slot，
   `RENAME` 不会产生 `CROSSSLOT`。`ClusterTargetRouter.restore` 现按 `RedisSlot.of(request.key())`
   路由（`ClusterTargetRouter.java:78-80`），临时 key 天然路由到同一连接。
2. **确定性命名**：同一真实 key 的临时 key 名固定。每次拆分开始先 `UNLINK` 临时 key，
   因此崩溃重跑会先清掉上一轮残留，无需全局清理扫描。
3. **保留命名空间冲突**：`TargetCommandSession.assertReservedNamespaceAvailable`
   （`TargetCommandSession.java:146-167`）会扫描 `__redis_ops_sync_*` 并 fail-closed。因此临时 key
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
StandaloneSyncTaskRunner / ClusterSyncTaskRunner.applyRdbEvent()
  └─ payload > 阈值 且 allowSafeSplit → SplitKeyRestoreWriter（每 channel 单线程，串行）
       ├─ TargetCommandSession.applySplitBatch(...)   # 新增，复用 WATCH fence 事务模式
       └─ 进度上报 FullSyncProgressTracker（新增 SPLITTING_KEY 阶段）
```

- **串行写线程**：拆分 key 的元素批次必须严格有序，不能进 `FullRestorePool` 的多 lane
  共享队列（lane 间无顺序保证）。拆分 key 本身按定义稀少，串行不影响整体吞吐。
- `applySplitBatch` 参照 `restoreBatch`（`TargetCommandSession.java:188-218`）实现：
  `WATCH fence → 校验 fence → MULTI → N 条元素命令 → EXEC`，失败 unwatch 并重试（沿用 3 次重连逻辑）。
- **不得复用 `apply()`**（`TargetCommandSession.java:310-423`）：其 destructive 检查会拦截
  `UNLINK`，且 prepare/confirm 两段式 checkpoint 语义不适用于拆分场景。
- 新命令白名单（拆分路径专用）：`SETRANGE / RPUSH / SADD / ZADD / HSET / RENAME / PEXPIREAT / UNLINK`，
  需在 `SyncCommandCapabilities` 中登记为拆分路径允许集，与增量命令策略分类解耦。

### 7.2 分批策略

- 批次边界：`batchBytes + next > full-apply-split-batch-bytes` **或** `batchCommands` 达上限即切批；
  单条元素超过批字节上限时独立成批（与 `FullRestorePool.java:143` 语义一致）；
- 收尾（RENAME + PEXPIREAT）必须是独立批次，绝不与元素批次混排——保证"要么完整旧值、要么完整新值"；
- 每批开始前 `leaseGuard.assertValid()`，批次间隔响应暂停/取消。

### 7.3 失败与恢复

| 场景 | 行为 |
|------|------|
| 元素批次失败 | 重连重试 3 次（沿用 `FullRestorePool.restoreBatch:168-182` 模式）；仍失败 → `UNLINK` 临时 key → 任务失败（全量从 RDB 头重来） |
| 进程崩溃/被接管 | 真实 key 未被触碰；新 runtime 全量重跑，拆分 key 首批前 `UNLINK` 清残留 |
| fence 被更高 generation 抢占 | `WATCH` 失败抛 `FencingException`，正常失败路径 |
| 暂停/取消 | 在批次边界生效；取消时 `UNLINK` 已创建的临时 key（writer 持有当前 key 引用） |

checkpoint 粒度维持"整个 RDB"不变（`StandaloneSyncTaskRunner.java:398-403`），拆分 key 可安全重写。

### 7.4 超时

`TargetCommandSession` 构造时统一 `socket.setSoTimeout(...)`（新增配置
`full-apply-socket-timeout-ms`，默认 300000 = 5 分钟）。`requireMultiKeyVersion` 中临时改超时的
逻辑不变。同时将 `restoreBatch` 单批内的命令字节总和写入执行日志，便于定位目标端慢 RESTORE。

## 8. 编码漂移（需在平台侧明示用户）

元素级重建不保留源端内部编码，目标端编码由目标配置与数据量重新决定：

- intset/listpack/ziplist 小编码 key：拆分后必然转为 hashtable/skiplist 等大编码；
- 影响：`OBJECT ENCODING` 输出不同、内存占用不同；功能语义等价。

缓解：**编码走 RESTORE 的 key 不受影响**；只有超过 64MB 的 key 才可能漂移，此类 key 本身
在源端也几乎必然是大编码。该结论需同步到平台文档与 precheck 提示。

## 9. 可观测性

| 项 | 说明 |
|----|------|
| `FullSyncProgressTracker` 新阶段 | `SPLITTING_KEY`（含当前 key 的字节数、已写入元素数/预估元素数），保持每秒聚合上报节奏（`FullSyncProgressTracker.java:13`） |
| Micrometer 计数 | `sync_split_keys_total`、`sync_split_elements_total`、`sync_split_batches_total`、`sync_split_fallback_total`（解码失败回退 RESTORE 次数） |
| 日志 | 拆分开始/结束 WARN 级各一条：taskId、key 字节长度、key SHA-256 前 8 字节、rdbType、元素数。**不打 key 内容**（防敏感数据泄露） |
| 指标落库 | `WorkerSyncFullProgress` 透传 split 阶段信息，Platform 侧展示沿用现有进度通道 |

## 10. 前置修复（P0，本方案的前置依赖）

| # | 修复 | 位置 |
|---|------|------|
| 1 | `FullRestorePool` 增加字节级背压：`Semaphore(fullApplyQueueMaxBytes)`，`submit()` acquire、`progress.applied` 后 release；条数队列降级为兜底 | `FullRestorePool.java:70,83-93` |
| 2 | `RestoreRequest` 去掉访问器 clone（构造时 clone 一次即可）；`estimatedBytes` 改读字段长度 | `TargetCommandSession.java:594-609`、`FullRestorePool.java:195-197`、`ClusterFullRestorePool.java:113-115` |
| 3 | RESTORE 目标连接设置 `SoTimeout`（同 §7.4） | `TargetCommandSession.java:56-72` |

P1~P3 不修，拆分路径自身会被同样的问题拖垮（元素批次同样要过队列、同样涉及 payload 拷贝）。

## 11. 风险与边界

| 风险 | 等级 | 缓解 |
|------|------|------|
| 拆分期间目标内存双倍（真实 key + 临时 key） | 中 | precheck 提示；阈值默认 64MB 起步 |
| 巨型 string 的 SETRANGE 分片期间目标持有稀疏 SDS | 低 | 文档明示；string 拆分可按需后开 |
| hash tag 使临时 key 名最长约 2×key 长度 | 低 | 相对 payload 可忽略；key 名 > 16MB 直接 fail-closed |
| 解码器 bug 导致数据不一致 | 高 | 解码失败一律回退 RESTORE；拆分完成后对抽样 key 做 `DEBUG DIGEST` 对比（仅测试环境启用） |
| `allowSafeSplit` 关闭的存量任务行为变化 | 低 | 无变化：开关不满足时维持现状路径 |
| 目标为旧版本 Redis 不支持 ABSTTL 等 | 低 | 拆分命令集均为 Redis 2.6+ 命令，不引入新版本依赖 |

## 12. 测试要点

1. **单元**：`RdbValueDecoder` 对 ziplist/listpack/intset/zipmap 的往返解码；临时 key 命名与
   `RedisSlot` 一致性（原 key 自带/不自带 tag、二进制 key）；批次切分边界；`Double.toString` 分数往返。
2. **集成**（Testcontainers + 真实 Redis）：
   - 1 亿字段 hash、1GB list 的全量拆分同步，`DEBUG DIGEST` 比对（若 §14-3 决定推迟 string 拆分，
     500MB string 场景改为验证回退 RESTORE 行为）；
   - 带 TTL / 无 TTL / 已过期 key 三种情况收尾正确性;
   - 拆分中途 kill worker → 重启后全量重跑 → 数据一致、无临时 key 残留；
   - fence 被抢占、lease 过期、目标主从切换三个故障注入；
   - stream / module / 字段级 TTL hash → 任务 fail-closed 且错误码正确。
3. **回归**：阈值 = 0 时全量同步行为与改动前逐字节一致（现有测试全绿）。
4. **覆盖**：新增类 80%+ 行覆盖（JaCoCo），`RdbValueDecoder` 与 `SplitKeyRestoreWriter` 优先。

## 13. 实施计划

| 阶段 | 内容 | 交付判据 |
|------|------|----------|
| P0 | 前置修复三项（§10） | 现有测试全绿 + 新增背压/克隆单测 |
| P1 | 解析器元素级路径（§5）+ `RdbValueDecoder` + Standalone 拆分链路（§6.1 Standalone 命名、§7） | Standalone 集成测试通过 |
| P2 | Cluster 拆分链路（hash tag 路由、`ClusterTargetRouter` 适配） | Cluster 集成测试通过 |
| P3 | 可观测性（§9）+ precheck 采样扫描（大 key：`SCAN` + `MEMORY USAGE`；不支持类型：`SCAN ... TYPE`，报告级提示） | 平台侧可见拆分指标与类型风险提示 |

每阶段独立合入 master，P0 可先行发布（独立价值：直接降低大 key OOM 概率）。

## 14. 待决问题

1. 超过 hard-cap 或其他无法处理的 key（见 §5.2 路由表最右列），是否允许配置为"跳过该 key + 告警"而非任务失败——跳过会造成目标静默缺数据，倾向不支持；
2. `full-apply-split-hard-cap-bytes` 是否需要小于 480MB（取决于线上 `proto-max-bulk-len` 是否被调小）；
3. 巨型 string 的 SETRANGE 路径是否首版就做（可推迟到 P1.5，string 大 key 场景相对少）。
