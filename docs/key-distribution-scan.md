# key 分布分析扫描策略

key 分布分析（`/api/v1/key-distributions`）按需对目标 Redis 做**只读游标扫描**，把 key 按规则分组计数，不落任何原始 key。本文描述扫描模型、预算与 checkpoint 语义、暂停/终止原因及运维排查方式。代码入口：`DistributionService`（application 模块）、`JdbcDistributionRepository` / `ReplicaScanSelection` / `BoundedDistributionRedis`（infrastructure 模块）。

## 扫描模型

### 任务创建与独占

- **创建即认领**：INSERT 任务与抢集群租约在同一事务完成（`createAndClaim`），接收请求的实例立即本地执行。不存在"新任务无主躺在队列里"的窗口，共享同一数据库的远端实例 sweep 抢不到它。
- **集群级互斥**：`key_distribution_runtime` 表按 cluster_id 持有租约（30 秒有效期，执行实例每 5 秒续租）。同集群同时最多一个扫描任务（含 preview）；本实例任务槽满时创建直接报 `DISTRIBUTION_BUSY`，集群被占报 `DISTRIBUTION_CLUSTER_BUSY`——不隐式排队。
- 集群模式只允许 db=0（`CLUSTER_REQUIRES_DB_ZERO`）；standalone 允许 0-15。

### 拓扑与多游标

- 启动时探测拓扑：cluster 模式每个 master 分片一个游标；standalone 是单分片。**优先扫 replica**（要求 `master_link_status=up`、无 sync in progress、ROLE connected），不可用则回退 master——扫描压力尽量不落在主节点。
- 拓扑指纹 = 各分片签名（master + slot 区间 + 实际选中节点）排序后 SHA-256。replica 切换、failover、reshard 都会改变指纹。
- 每个分片一个 SCAN 游标，各自独立推进；分片完成以游标回到 `0` 为准（且该页未被预算截断）。

### 并行与节流

- 分片并行：`min(分片数, distribution.max-shard-parallelism)` 个 worker 从待扫队列各领一个分片，**每分片一条 pinned 连接**（复用做整个分片的扫描，不再每页重连）。
- 节流按页：每个 worker 扫完一页 sleep 到 `本页开始时刻 + spec.scanIntervalMillis`。限速是**每分片**的页间隔，无共享限速器——总页速率 ≈ 并行度 / interval，总 key 速率还受每页 key 数影响。
- 单页 `SCAN COUNT = spec.scanCount`。注意 Redis 的 COUNT 只是**下界提示**，实际返回会超发，因此 scanCount 不宜贴着 `max-response-elements`（默认 5000）：实测 5000 必触发 `SCAN_ELEMENT_LIMIT`，**建议 ≤3000**。

### 三层预算

- **墙钟**（`spec.durationSeconds`，≤ `max-duration-seconds`）：累计扫描时长，跨 pause/resume 叠加（checkpoint 记 `elapsedMillis`，重启后从剩余额度继续）
- **观测总数**（`spec.maxObservations`）：观测到的 key 总数上限，达到即停（`observed` 计数）
- **分组容量**（`spec.capacity`，≤ `max-groups`）：FIXED 模式超出容量的 key 计入系统桶 `GROUP_LIMIT`；TOP_K 模式为 Space-Saving 近似（entry 带 error 上下界）。达到容量置 `capacityReached=true`，**不会**终止扫描

墙钟或观测数用尽 → `INCOMPLETE / BUDGET_REACHED`。

### checkpoint 语义

- 协调线程每 **1 秒**做一次快照：指纹 + 各分片游标 + observed + elapsedMillis + estimatedTotal + 全部分组计数（JSON ≤2MB）。
- `estimatedTotal` 是扫描启动时逐分片 `DBSIZE` 求和的**进度分母**（0=未知，仅详情接口从 checkpoint 填充到任务 DTO）。采样失败只降级为无进度条，不影响扫描；committed 值 >0 时 resume/接管直接复用，分母跨暂停保持稳定。
- **首存同步**：第一次持久化失败（DB 不可用/状态漂移）立即停止，任务保持可接管，避免白扫整个预算。此后走单写线程 **latest-wins**：上一笔还没写完就跳过本笔，慢 DB 链路不阻塞扫描线程。
- checkpoint 与租约心跳解耦：实例崩溃/发布/断电**无需人工干预**——租约 30 秒过期后，任一实例的 1 秒 sweep 认领任务，从 checkpoint 续扫（计数器、游标、已耗时全部恢复）。
- resume 前校验指纹：与当前拓扑不符 → `INCOMPLETE / TOPOLOGY_CHANGED`（游标属于旧节点，续扫无意义）。
- 旧版本 checkpoint JSON 里的多余字段由 Jackson 忽略，向前兼容。

## 状态机与暂停/终止原因

状态：`QUEUED → RUNNING → COMPLETED / INCOMPLETE / PAUSED / FAILED`，任意非终态可 `CANCELLED`。控制接口 `POST /tasks/{id}/{pause|resume|cancel}` 需 `If-Match`（version）+ `Idempotency-Key`。

**RUNNING 期间的 checkpoint 不推进 version**（冻结在认领时的版本），因此 pause/cancel 用几秒前的 version 也能一次成功；用户操作与终态写入才 `version+1`。

### 可恢复（PAUSED，可 resume）

- **PAUSED / NULL（用户暂停）**：pause 控制动作，运行线程被中断且不写终态。resume 后从 checkpoint 续扫
- **PAUSED / SCAN_TIMEOUT**：分片连续 3 次命令超时（`command-timeout-ms`，上限 3s）。可 resume；若 replica 持续过载会再次暂停，考虑换节点或调大 interval
- **PAUSED / DEPLOYMENT_BUDGET_CHANGED**：部署侧收紧了 `max-scan-count` / `min-scan-interval-ms`，已存在任务的 spec 违反新预算。可 resume，但前提是先把配置放宽回满足该 spec，否则 resume 后立即再次 PAUSED；或取消后按新预算重建

### 终态（不可 resume，只能重建）

- **INCOMPLETE / TOPOLOGY_CHANGED**：resume 时指纹不匹配 / 运行中 30 秒周期复检发现漂移 / 完成前最终校验失败（failover、reshard、replica 切换）
- **INCOMPLETE / BUDGET_REACHED**：墙钟累计用尽或 maxObservations 达到；调大 durationSeconds / maxObservations 后重建
- **INCOMPLETE / SCAN_ELEMENT_LIMIT**：单页返回元素超过 `max-response-elements`（scanCount 过大被 COUNT 超发顶死）；降低 scanCount（≤3000）重建
- **INCOMPLETE / SCAN_TOPOLOGY_UNSTABLE 或 LEGACY_RATE_CONFIGURATION**：拓扑探测阶段不稳定 / 旧版 keysPerSecond 规格；重建（新规格用 scanCount+scanIntervalMillis）
- **FAILED / SCAN_CONNECTION_FAILED 等**：连接失败等真异常。若首份 checkpoint 尚未落盘，不改判 FAILED 而是回到 QUEUED 并保留租约 60 秒作冷却（requeue 兜底），让真正连得上该集群的实例接管
- **CANCELLED**：用户取消

异常 reason 归一化：非 `SCAN_*` / `DISTRIBUTION_*` / `INVALID_DISTRIBUTION_*` 形态的消息一律记 `SCAN_FAILED`。

### resume 的执行路径

只有 `PAUSED` 可 resume。resume 把任务置回 `QUEUED` 并清掉运行时租约，接收请求的实例尝试本地认领执行；本地槽满则由 1 秒 sweep 接管。重启后从 checkpoint 恢复计数与游标，墙钟扣掉已耗时。

## 部署参数（distribution.*）

默认值见 `bootstrap/src/main/resources/application.yml`；Apollo/`./config/` 可覆盖。**收紧方向可调，放大方向受硬上限约束**（超界启动即失败 `INVALID_DISTRIBUTION_*`）。

- **concurrent-tasks**（默认 1，范围 1-2）：单实例并行扫描任务槽
- **max-duration-seconds**（默认 21600，上限 21600）：单任务墙钟预算上限（6h）
- **max-scan-count**（默认 5000，范围 1-5000）：spec.scanCount 上限（单页 COUNT 提示）
- **min-scan-interval-ms**（默认 10，范围 10-60000）：spec.scanIntervalMillis 下限（每分片页间隔）
- **max-groups**（默认 1000，范围 1-1000）：spec.capacity 上限
- **max-shard-parallelism**（默认 4，范围 1-32）：分片并行 worker 数上限
- **max-response-bytes**（默认 1048576，上限 1MB）：单页响应字节上限
- **max-response-elements**（默认 5000，上限 5000）：单页元素上限（scanCount 需留超发余量）
- **max-key-bytes**（默认 4096，上限 4096）：单 key 长度上限，超长计 KEY_TOO_LONG
- **max-shards**（默认 256，范围 1-256）：分片数上限
- **connect-timeout-ms**（默认 2000，上限 2000）：建连超时
- **command-timeout-ms**（默认 3000，上限 3000）：命令超时（连续 3 次即 PAUSED/SCAN_TIMEOUT）

任务级 spec 在创建时校验：`scanCount ≤ max-scan-count`、`scanIntervalMillis ≥ min-scan-interval-ms`、`durationSeconds ≤ max-duration-seconds`、`capacity ≤ max-groups`，违反报 `DISTRIBUTION_DEPLOYMENT_BUDGET_EXCEEDED`。部署侧事后收紧配置只影响存量任务的 resume（见「状态机与暂停/终止原因」的 DEPLOYMENT_BUDGET_CHANGED）。

## 运维排查指引

1. **看真实失败原因**：API 只返回归一化 code，真实异常在 platform 日志搜 `distribution task {id} ended as`（`DistributionService.execute` 的 warn 行）。
2. **任务卡 RUNNING**：先确认实例是否存活。正常情况租约 30 秒过期后 sweep 自动接管续扫，无需手动取消；持续不动再查 `key_distribution_runtime.lease_until` 是否被续（心跳线程活着但扫描线程死了属 bug，收集日志反馈）。
3. **FAILED / SCAN_CONNECTION_FAILED, observed=0**：认领实例连不上目标集群。requeue 机制会让可达实例接管，反复出现则检查平台实例到 Redis 的网络/凭证。
4. **PAUSED / DEPLOYMENT_BUDGET_CHANGED**：对照当前 Apollo 配置与任务 spec（`spec_json`），要么放宽配置，要么取消重建。
5. **INCOMPLETE / TOPOLOGY_CHANGED**：期间发生过 failover/reshard/replica 切换，checkpoint 游标失效。等拓扑稳定后重建任务即可，旧结果仍可查看。
6. **SCAN_ELEMENT_LIMIT**：把 scanCount 降到 3000 以下重建。
7. **吞吐调优**：单分片速率受 RESP 协议地板限制，加吞吐优先加 `max-shard-parallelism`（每分片一条连接，压力随并行度上升，replica 优先已缓解）；scanCount 加大减少页数但注意超发上限。
8. **容量**：`DISTRIBUTION_BUSY`（本实例满槽）等在跑任务结束或调 `concurrent-tasks`（≤2）；`DISTRIBUTION_CLUSTER_BUSY`（同集群他任务持有）需等或取消对方。
9. **preview 限制**：预览同样占集群租约，上限 15 秒 / 1000 key / 256KB 样本，集群级 15 秒节流，busy 报 `REQUEST_IN_PROGRESS`。
10. **数据保留**：终态任务 30 天后由小时级 cleanup 批量删除（每批 20 条），分组结果随任务一并删除。

## 与其他扫描 job 的关系

分布分析与治理预检、风险扫描等共用 `ReadOnlyScanPort.Session` 的 pinned 连接语义，但任务是独立的（不自 `async_job` 队列派发），互不影响；测试环境多实例共用数据库时，创建即认领模型保证任务只在可达实例上执行。
