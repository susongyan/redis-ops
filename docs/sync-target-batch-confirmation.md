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
checkpoint/fence 键；此首批尚未完成真实 Cluster 故障验收，不宣称阶段一已全部完成。

## 恢复与兼容性

- 新 Worker 读取 pending（包括 fence 发布、checkpoint、去重入口）统一抛出
  `BLOCKED_TARGET_BATCH_UNCONFIRMED`。新 generation 不得忽略它。
- 旧 Worker 无法解析 pending 格式，会失败关闭；不承诺混合版本协同运行，升级应先停相关任务。
- 崩溃在准备后、业务执行前也会保守阻塞。这是选择避免重复执行的代价，不表示业务一定已经写入。
- 不提供自动清除 pending 或仅修改 offset 的恢复操作。必须核实或显式重新建立迁移基线；
  不自动清空目标，也不自动回滚已经发生的业务写入。
- 数据库级 FLUSH 会抹去 pending/fence，执行器现阶段提前阻止并返回
  `BLOCKED_DESTRUCTIVE_BATCH_CONFIRMATION`，即使旧任务策略允许危险命令也不执行。
  现有能力展示需在后续契约联调时对齐；在对齐前不部署此 Worker 到业务任务。
- 本机制增加两个事务往返；吞吐、延迟及 Cluster 部分分片成功后的恢复需要后续压测。
- 本批修复覆盖增量 apply；全量 restore、FUNCTION LOAD 等既有路径不应据此宣称获得新确认协议。

## 首批验证

`TargetBatchRedisTest` 通过 `SYNC_BATCH_TEST_REDIS=127.0.0.1:<port>` 显式连接一次性 Redis。
测试仅使用随机命名空间，不执行 FLUSH。普通构建无该环境变量时跳过该组真实 Redis 测试。

覆盖：成功后重复提交只执行一次、局部运行错误、错误后重启/接管阻塞、三个 EXEC 回复丢失、
业务 EXEC 发送前断线、预置 pending 的崩溃恢复，以及危险清空在发送前被拒绝。
故障代理读取真实 Redis 回复后丢弃，未用模拟成功响应替代真实命令效果。

阶段一仍需补齐：真实 Cluster 与 Redis 6.2 故障矩阵、能力协议/领取兼容性、吞吐对比和部署门禁。
通过这些门槛后才冻结最终协议并开放阶段二命令。
