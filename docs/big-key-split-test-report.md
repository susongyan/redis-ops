# 大 Key 拆分同步（全量阶段）测试报告

日期：2026-11-10 ｜ 范围：P0-P3 全链路（sync worker / platform / 前端）｜ 结论：**全部通过**

| 阶段 | 内容 | 结果 |
|---|---|---|
| P0 | 前置改造：RdbEvent 唯一副本、RestoreRequest 单 clone、SoTimeout 贯通、restore 队列字节预算 | ✅ |
| P1 | Standalone 元素级拆分链路 + 跳过三件套 | ✅ |
| P2 | Cluster slot-safe 拆分链路（per-slot 围栏、三情形命名） | ✅ |
| P3 worker | precheck 采样 + bulk-len 硬检查 | ✅ |
| P3 B/C | 平台告警接线 + 前端跳过清单 | ✅ |

构建基线：`mvn clean verify` **209/209 绿**（+54 跳过，含环境门控 IT）。

## 1. 单元测试

### P0 内存与队列（sync-service / sync-protocol）

- `RdbEventTest`：keyValue/functionLibrary 访问器**直接交出数组零拷贝**——唯一副本语义钉死。
- `RestoreRequestTest`：构造器恰好一次防御性拷贝；`estimatedBytes` = key+payload+wire 开销三处同式。
- `FullRestoreQueueBudgetTest`：小额请求按字节扣许可；**超预算请求独占整个预算排队而非拒绝**；<1KB 配置拒启。

### P1 解析器与写入器

- `RdbStreamParserSplitTest`（12 用例）：小 key 与 legacy 路径逐字节一致；list/hash/set/zset 各自的元素级流式拆分；
  quicklist 跨 ziplist/listpack 节点；**LZF 压缩 blob 超阈值先解码再拆、损坏则回退**；超 hard-cap 值跳过后解析继续；
  流中 hard-cap 中止已开的拆分；MODULE_2 仅显式 opt-in 才跳过；EXPIREATE 进 begin 事件。
- `SplitKeyRestoreWriterTest`：临时 key 确定性且**指纹不含 key 内容**（sha256 前 16hex）；每个可拆类型命令映射；
  **目标 OOM 识别覆盖裸/包装两种错误形态**（不重试原样透出）；配置校验与批量继承。
- `RdbValueDecoder` 金向量：与 redis/redis 7.4 逐字节对齐，揪出 zipmap free 字节位置、listpack 双推进两处真 bug。

### P2 Cluster 路由

- `ClusterSplitChannelTest`：**临时 key 与原 key slot 严格一致**（三情形：有效 tag 包 tag / 无 `}` 整 key 包裹 /
  含 `}` 用无括号 ASCII 碰撞 tag）；同 tag key 共享临时名；命名确定性与指纹分离。12 类病态 key 断言。

### P3 precheck

- `SyncPrecheckSampleTest`：拆分禁用（阈值 0）/policy 拒绝/预算 0 三种跳过路径；bulk-limit 检查同前两门控跳过；
  `classifySample` 覆盖 module 类型、超 hard-cap、路径估算；负采样预算拒启。
- `SyncPrecheckSamplingRedisTest`（活库）：**超限 key 只 WARN 不翻 passed**、bulk-limit 对活目标生效。

### P3 B/C 接线

- platform `SyncSkipAlertServiceTest` + `AlertServiceTest`：SPLIT_KEY_SKIPPED 前缀扫描 → LARGE_KEY_FOUND 触发；
  OPEN/ACKNOWLEDGED 去重窗口；RESOLVED 后窗口内新跳过重新打开。
- 前端 `syncSkipList.test.js`：消息解析（正则不锚定行尾——契约 8 允许字段追加）、原因标签/rdb 类型词表、
  skipBytes 人读尺寸。

## 2. 集成测试（环境门控，本地真实 Redis）

- Standalone IT `splitsOversizeKeysThroughTempKeysAndSkipsOverHardCap`：scratch 6390/6391（Redis 5.0.7，
  ziplist 老编码路径）——超限经临时 key 拆分落地、RENAME+PEXPIREAT 收尾、超 hard-cap 跳过+事件，测完即关。
- Cluster IT `splitsOversizeKeysThroughSlotSafeTempKeys`（3 主集群，4/4 场景）：
  三命名情形 slot 正确、**TTL 保真**（PEXPIREAT 随批）、hard-cap 跳过事件落 `sync_task_event`、
  **三节点零残留**（临时 key/围栏键全清）、**拆分后增量继续**。
- 另有存量 `convertsStandaloneToClusterAndSplitsSafeMultiKeyWrites`（跨形态转换）回归通过。

**测试钉死的两条语义**（写用例时勿再踩）：
1. 路由尺寸 = **wire 尺寸**：LZF 压缩后的大小决定阈值/hard-cap，高压缩 200MB string 走小 key RESTORE 是合法路径；
   超限样本必须用随机不可压数据（`'h'.repeat()` 会因高压缩比直接栽）。
2. `allowSafeSplit` 必须显式 true：Jackson 对 `{}` 反序列化得 false（与 `SyncCommandPolicy.strict()` 工厂默认不同）。

## 3. 端到端验证

### 3.0 拆分独立统计 E2E（2026-11-10，任务 34）

- 拓扑：源 cluster 3（共享集群，bkwrite.jsh 造的 7 个 2001 万元素级大 key 样本 ~9GB）→ local-c1（id=5，FLUSH），worker local-sync。
- 结果：precheck READY → **CAUGHT_UP**，三通道 COMPLETED；`lane=-1` 汇总 **splitKeys=5 / splitBytes=1.90GB、splitSkippedKeys=2 / splitSkippedBytes=1.25GB**，同时 parsedKeys=7、appliedKeys=0 ——拆分计数独立于 RESTORE 计数的语义在真实数据上成立（任务 32 旧构建下这 5 拆 2 跳只能靠事件数出来）。
- 告警活体：真实 SPLIT_KEY_SKIPPED 事件 → 60s 轮询内 `alert_event` OPEN（LARGE_KEY_FOUND / SYNC_TASK / P1，首轮 value=1.0，第二条落在 OPEN 去重窗内属设计行为）。
- 前端活体（Playwright 真登录 + 真数据）：全量进度面板汇总行「拆分大 Key **5 个 / 1.8 GiB（跳过 2 个 / 1.2 GiB）**」+ 每通道列（`2 个 / 856.2 MiB` 等），7 项断言全过（截图 /tmp/ui-audit/live-split-stats.png）。
- 测后全清（用户批准）：任务 34 CANCELLED、共享集群的 7 个 bk:* UNLINK（各返回 1）、local-c1 三主 FLUSHDB（dbsize=0）、临时告警规则与合成事件删除。
- 附带（与拆分无关）：任务 33 启动失败 = 源侧共享集群瞬时 CLUSTERDOWN 落在写围栏 WATCH 上，worker 瞬态重试仅 5 次×15s 未扛过；节点恢复后重跑即过。另修复调度器全挂根因（RedisCollectorWorker 无界 Lettuce shutdown 挂死 platform-scheduler-1 达 5.2h）。

### 3.1 真实数据 E2E（2026-11-10，任务 31）

- 拓扑：源 cluster 4（共享测试集群，凭据经 AES-GCM 密钥集解密）→ local-c1（id=5，FLUSH 模式），
  worker local-sync，4 lane 并行 apply。
- 结果：创建 → CAUGHT_UP **3m26s**；全量 1.9GB RDB / **1411 万 key**（691M+493M+721M），apply 2m35s；
  **appliedKeys == parsedKeys 逐通道完全相等**；本地 dbsize 与源吻合；增量 RPO=0；worker RSS 1.17GB。
- 拆分路径：真实数据**无 >64MB 的 key** → 拆分/跳过计数器全 0、skippedKeys=[]、无 SPLIT 事件——
  对该数据集属预期行为；拆分路径本身的正确性由 §2 IT 覆盖。
- 附带发现（与拆分无关的存量 bug，另有记录）：FINISH 排空 60s 超时在持续写入源上复现（任务终态 FAILED）。

### 3.2 告警链路 E2E（2026-11-10，合成事件）

- 临时 LARGE_KEY_FOUND 规则 + 挂终态任务的 2 条 SPLIT_KEY_SKIPPED 事件 → 平台 60s 轮询内
  `alert_event` OPEN（value=2.0）；两轮去重零重复；RESOLVED 语义验证；skippedKeys 接口返回清单；测后数据全清。

### 3.3 前端走查（Playwright + 接口 mock，17 项）

- 任务详情「跳过清单」区（全量进度与运行实例之间，5s 轮询）、FINISH 弹窗跳过提醒、
  precheck findings 标签渲染、`SPLITTING_KEY` 状态文案；mock 要点：detail 必含 task 键、
  clusters 回 `{items:[]}`、cluster-relations 路径名。

## 4. 指标与观测（上线后核对项）

- worker `/actuator/prometheus`：`sync_split_batches_total` / `sync_split_elements_total`（per-channel）、
  `sync_split_fallback_total`——真实无超限数据集应为 0 且不增长。
- 平台：`alert_event(resource_type=SYNC_TASK)`；前端任务详情跳过清单与 FINISH 提醒。
- **拆分独立统计（2026-11-10 增补，契约 9 / V45）**：`sync_full_progress` 四列
  `split_keys/split_bytes/split_skipped_keys/split_skipped_bytes` 独立统计；
  **applied 语义修订（同日）**：拆分 RENAME 落地的 key 计入 `applied_keys/applied_bytes`
  （只并入 lane=-1 聚合行）——纯拆分通道「已写入 Key」不再恒 0，`parsed = applied + skipped`
  三者闭环；`parsed_keys` 不变。前端全量进度面板显示"拆分大 Key N 个 / X（跳过 M 个 / Y）"
  汇总与每通道列。此前的观测缺口：纯拆分通道 applied 恒 0、跳过只有事件流——
  任务 32 现场确认（5 拆 + 2 跳过在旧构建下只能靠事件数出来）。
- **跳过事件格式 v2（2026-11-10，契约 8）**：`key=原key名`（行尾、容忍空格）替代指纹，
  `elements=` 集合类声明总元素数（不可知 0）；前端「Key 指纹」列改「Key」列并新增「元素」列，
  v1 存量事件回退指纹渲染。mock 走查 7 项全过（/tmp/ui-audit/audit-v2.mjs）。
- 测试环境联调前置：需在测试环境创建 LARGE_KEY_FOUND 告警规则，否则跳过只落事件不告警。

## 5. 版本

覆盖 P0–P3 全部实现与本文档所述验证。
设计文档见 `big-key-split-design.md`（含 P1/P2/P3 勘误与 §5.4/§7 决议）。
