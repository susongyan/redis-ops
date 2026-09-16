# 同步目标批次确认：阶段一实现记录

状态：第一阶段首批实现，尚未发布到运行中的 Worker。多 Key 扩展和源端事务组装尚未启用。

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
  现有能力展示需在后续契约联调时对齐；在对齐前不部署此 Worker 到业务任务。
- 本机制增加两个事务往返；吞吐、延迟及 Cluster 部分分片成功后的恢复需要后续压测。
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

阶段一仍需补齐：能力协议/领取兼容性、吞吐对比和部署门禁；拓扑变更故障矩阵仍需扩展。
通过这些门槛后才冻结最终协议并开放阶段二命令。
