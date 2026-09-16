# 同步目标批次确认：阶段一实现记录

状态：第一阶段开发验收完成，尚未发布到运行中的 Worker。多 Key 扩展和源端事务组装尚未启用。

## 为什么需要三阶段

Redis EXEC 的运行期错误不回滚已执行命令。旧逻辑在同一个事务里写成功 checkpoint，
可能出现业务局部失败而 checkpoint 已推进。新增逻辑不在业务 EXEC 内更新成功位置。

## 持久化状态与写入顺序

本批次不增加 MySQL 表或 Redis Key。复用每个目标分片原 checkpoint 键，值允许以下两种形态：

- 原有 5/6 字段成功 checkpoint：继续兼容读取。
- `pending-batch-v2:<随机批次ID>:<旧checkpoint的Base64>:<拟提交checkpoint的Base64>`。
  首批无旧 checkpoint 时该段为空。只含恢复元数据，不包含命令或 Key/value。

| 阶段 | 操作 | 失败后的含义 |
| --- | --- | --- |
| 准备 | WATCH fence/checkpoint，校验资格及已确认 offset；事务写入 pending | WATCH 冲突可重新准备；回复不确定时重连读取，pending 必须阻塞 |
| 执行 | WATCH fence/checkpoint，核对同一 pending；事务执行业务命令，不写成功 checkpoint | 局部错误、回复丢失、资格丢失均保留 pending，关闭连接并阻塞 |
| 确认 | 当前调用已核对每条成功回复，再 WATCH 同一 fence/pending；事务替换为成功 checkpoint | 成功回复丢失时，后续读取成功 checkpoint 可去重；仍为 pending 则不能重放 |

空命令游标推进保持原有 WATCH/MULTI/EXEC 单阶段路径，但读到 pending 也必须阻塞。
每次写入前校验本地租约；pending token 防止不同调用互相确认。Cluster 继续使用同 Slot 的
checkpoint/fence 键；已验证真实三主节点 Cluster 的同 Slot 批次故障及跨分片部分成功恢复行为。

## 恢复与兼容性

- 新 Worker 读取 pending（checkpoint、去重入口）统一抛出
  `BLOCKED_TARGET_BATCH_UNCONFIRMED`。接管先在 WATCH 保护下发布新 generation fence，
  随后同样阻塞并保留 pending，确保旧 Worker 不能继续执行或确认。新 generation 不得忽略它。
- 旧 Worker 无法解析 pending 格式，会失败关闭；不承诺混合版本协同运行，升级应先停相关任务。
- 崩溃在准备后、业务执行前也会保守阻塞。这是选择避免重复执行的代价，不表示业务一定已经写入。
- 不提供自动清除 pending 或仅修改 offset 的恢复操作。必须核实或显式重新建立迁移基线；
  不自动清空目标，也不自动回滚已经发生的业务写入。
- 数据库级 FLUSH 会抹去 pending/fence，执行器现阶段提前阻止并返回
  `BLOCKED_DESTRUCTIVE_BATCH_CONFIRMATION`，即使旧任务策略允许危险命令也不执行。
  能力查询、命令规划器及页面现已统一为不可配置的阻塞；历史允许字段保留但不生效。
  Platform 和 Worker 需要同时使用 sync-contract 0.1.1，不能只升级页面。
- 本机制增加准备和确认事务及其 WATCH/GET 往返；本地开销对比见下文，生产吞吐需单独验收。
- 业务命令保持有界批量发送，再逐条检查 QUEUED 和 EXEC 回复；不为每条业务命令增加独立网络往返。
  排队期参数错误会关闭事务连接、保留 pending，不执行已排队的其他命令，也不自动重放。
- 本批修复覆盖增量 apply；全量 restore、FUNCTION LOAD 等既有路径不应据此宣称获得新确认协议。

## 首批验证

`TargetBatchRedisTest` 通过 `SYNC_BATCH_TEST_REDIS=127.0.0.1:<port>` 显式连接一次性 Redis。
测试仅使用随机命名空间，不执行 FLUSH。普通构建无该环境变量时跳过该组真实 Redis 测试。

覆盖：成功后重复提交只执行一次、局部运行错误、错误后重启/接管阻塞、三个 EXEC 回复丢失、
业务 EXEC 发送前断线、预置 pending 的崩溃恢复，以及危险清空在发送前被拒绝。
故障代理读取真实 Redis 回复后丢弃，未用模拟成功响应替代真实命令效果。

2026-09-16 补充验证：Redis 6.2.14 Standalone 与 Redis 7.4 三主节点 Cluster（slot 0）
分别执行同一组 11 个案例，均通过。除上述回复丢失矩阵外，覆盖准备／执行／确认三个
WATCH–EXEC 窗口中的新 generation 接管：旧写者不能越过 fence，成功位置不能越过未确认效果。
Cluster 使用 `SYNC_BATCH_TEST_CLUSTER_SLOT0=127.0.0.1:<slot0节点端口>`；
另通过 `SYNC_BATCH_TEST_CLUSTER_MASTERS=127.0.0.1:<节点1>,127.0.0.1:<节点2>,127.0.0.1:<节点3>`
运行 `ClusterBatchRecoveryRedisTest` 两个案例（按 slot 范围升序传入三个节点）。测试使用真实
Cluster 发现结果，只转换 Docker NAT 地址，不模拟目标回复：

- 第一分片成功、第二分片局部失败时，全局游标保持旧位置；接管后第一分片 INCR 不重复，第二分片继续阻塞。
- 三分片全部确认成功后，接管重放同一 offset 不重复执行，各分片计数保持为 1。

这些案例不替代 MOVED/ASK 或主从切换的完整验收。

后续增加排队期错误测试及 `COMMAND GETKEYS` 元数据交叉校验。Key 解析测试覆盖 20 个
首批命令／变体；Redis 6.2 与 7.x 返回 Key 的顺序可能不同，比较完整多重集合而非返回顺序。
纯协议测试另覆盖非法 Key 数量、整数溢出、重复／未知选项和无效权重，错误文本保持固定。
COPY 的目标 DB 只被解析记录，后续准入必须检查跨 DB 边界，不能据此直接放行。
参数依据：[COPY](https://redis.io/docs/latest/commands/copy/)、
[LMOVE](https://redis.io/docs/latest/commands/lmove/)、
[ZUNIONSTORE](https://redis.io/docs/latest/commands/zunionstore/)。首批限制在 6.2/7.x 参数范围。

以上确认协议作为后续阶段的开发基线冻结。MOVED/ASK、主从切换、持续高负载和生产网络
验收仍属于集成发布门槛，不能因本阶段完成而宣称整个迁移扩展已可生产发布。

## 策略版本领取基线

本次基线 Worker 只声明并领取 `v1`。任务控制 Job、按运行实例路由的 Job、过期租约发现和
运行租约更新均在 MySQL 条件中检查策略版本。缺少版本、JSON null 和空字符串继续按旧版 v1
处理；未知版本、大小写不同版本、非字符串版本和非对象策略不领取。执行器创建入口还会解析
整个策略，在读取 Redis 凭据、连接目标或领取 runtime 前失败关闭；异常不包含原始策略文本。

Worker 的 `/actuator/info` 新增只读 `syncCapabilities`：

```json
{
  "commandPolicyVersions": ["v1"],
  "batchConfirmationVersion": 2,
  "policyAwareClaims": true,
  "sourceTransactions": false
}
```

它是部署核对信息，不是已实现的 Worker 自动注册／能力调度中心；新多 Key 策略和源事务仍未启用。

升级必须分两步：先停止相关任务并把**所有旧 Worker**升级到带 `policyAwareClaims` 的基线，
核对每个进程能力；再部署未来支持新策略的 Worker 和 Platform，并开始创建新策略任务。
更早版本没有策略领取条件，无法通过只升级一部分进程使其自动具备此能力，禁止跨越基线混部。
旧任务保持 v1；不能直接修改其保存策略来扩大同步范围。

`WorkerPolicyMysqlTest` 使用 `SYNC_POLICY_TEST_MYSQL` 指定的一次性本地 MySQL，
每个案例创建随机 `sync_policy_test_*` 库并只删除自身创建的库，验证实际 MyBatis SQL，
不依赖生产/项目数据库，也不修改现有 schema。普通构建未提供环境变量时跳过。

## 有界吞吐对比

`TargetBatchThroughputRedisTest` 通过 `SYNC_BATCH_BENCHMARK_REDIS=127.0.0.1:<port>` 显式启用。
每组预热 20 批，再测量 100 批；批量大小为 1、100，各 3 轮交替新旧路径顺序。
只使用随机测试计数器，逐组校验最终计数和 checkpoint，不清空数据库。
旧路径仅在测试内复现成功场景的 WATCH/GET/MULTI/EXEC 及 checkpoint 写入，不进入应用代码。

2026-09-16，本机 Colima Redis 7.4、JDK 21（编译目标 17）、未开启 Redis 持久化：

| 每批命令数 | 旧成功路径 100 批耗时（ms，3 轮） | 新确认路径 100 批耗时（ms，3 轮） |
| --- | --- | --- |
| 1 | 2707 / 2124 / 2654 | 8745 / 7113 / 6702 |
| 100 | 2859 / 3551 / 2762 | 7910 / 8135 / 7230 |

100 条批次耗时中位数约为旧路径的 **2.77 倍**。这是额外确认往返的实测代价，不可描述为
“无性能影响”。本测试不含 PSYNC/RDB、MySQL、spool、真实 Key/value 分布、跨机房延迟或
完整生产资源约束，不可用来承诺生产容量；不为提高数值而移除 pending、fence 或租约检查。

## 部署门禁与验收范围

1. 当前开发验证：contract `clean install`（含 verify）、Worker `clean verify`、Platform `verify`、
   前端 `npm ci && npm run build`；能力 HTTP 回归和桌面／390px 页面检查。隔离 Redis 故障及
   MySQL 版本领取证据见上文。未提供环境变量的可选集成测试仍会跳过，不等于全矩阵通过。
2. 本阶段不自动发布、推送或重启业务 Worker；首次全量经确认的目标清空流程没有改变。
3. 上线前停止相关任务与所有旧 Worker；按上文核对兼容基线、已确认 checkpoint 和租约释放。
   有 pending 的目标先核实，禁止删除 pending、修改 offset 或直接降级来绕过。
4. Platform/Worker 使用同一 `sync-contract:0.1.1`，同时交付新版前端；历史清空允许标记不再生效，
   提前告知运维：源端出现 FLUSH 会阻塞任务，不会跳过后继续同步。
5. 新命令及事务功能仍需阶段二至四的独立测试；正式发布前补齐拓扑变化、慢网／故障、持续负载
   与资源边界验收，并以实际环境吞吐决定发布规模。不满足门槛时不开放业务迁移。
