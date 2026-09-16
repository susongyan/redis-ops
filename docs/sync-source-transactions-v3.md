# 同步策略 v3：源事务与 Lua effects

状态：阶段三核心开发验收完成；未部署到现有进程，生产集成门禁仍见下文。

## 版本与范围

契约 artifact 为 `sync-contract:0.3.0`。任务显式使用 `commandPolicy.policyVersion=v3`；
默认仍为 v1，v2 仍只增加多 Key 命令准入，不自动支持源事务。Worker 领取 SQL 和能力声明同步区分版本。
先升级 Worker，再允许创建 v3 任务；旧策略不原地升级，过滤范围扩大需重新建立全量基线。

v3 处理复制流中的 `MULTI ... EXEC`，包括 Redis 6.2 / 7.x 的 Lua 写入 effects。
不在目标重放 EVAL / EVALSHA 脚本原文，不支持未知 Module 命令或跨 Slot 原子事务。

## 提交边界

- 每个源通道单独组装事务，跨 socket 读取、spool 分段和 100 条应用批次保留组装状态。
- EXEC 前不发送内部业务命令；成功 checkpoint 只停在事务之前或 EXEC 之后。
- 所有写入都在范围外时整体跳过；涉及范围内外写入或范围外输入依赖时整组阻塞。
- Cluster 整个事务必须同 Slot；不把事务拆成多个 Slot 提交。
- SELECT 可在事务内出现：只更新源 DB 跟踪，不发送到目标；跨 DB 的混合业务范围仍阻塞。
- 源端 MULTI / EXEC 标记被消费，目标执行器使用自己的批次确认协议，不嵌套 MULTI。
- spool/live 重叠投递按已消费和已提交 offset 消除重复组装；进程重启从目标已确认 checkpoint 恢复。
- 暂停不提交半事务；短暂断流从最后完整接收 offset 继续组装。终止输入仍缺 EXEC 时阻塞。
- RESP 半帧超时强制重连，不能从半帧继续解析。结果不确定仍保留 pending，不盲目重放。

## 容量和失败关闭

| 边界 | v3 限制 |
| --- | --- |
| 单个复制命令帧 | 16 MiB，最多 65,536 参数；分配 bulk 前检查 |
| 单个源事务 | 10,000 条内部命令、16 MiB 编码字节（包含事务标记） |
| 每通道待应用队列 | 10,000 条及 32 MiB 编码字节预算共同限制 |
| spool 回放批次 | 最多 100 条，累计超过 1 MiB 前提交当前批次；大单条仍受帧限制 |
| 加密 spool 记录读取 | 32 MiB，回放和清理检查都先校验记录长度 |

字节预算不是 JVM retained heap 等价物：命令对象、复制、解码和加密缓冲需要额外内存，
且多个源通道、任务会叠加。生产堆大小和并发需要资源验收，不能据此声称整个 JVM 不会 OOM。

固定阻塞原因包括 `BLOCKED_TRANSACTION_MIXED_SCOPE`、`BLOCKED_TRANSACTION_CROSS_SLOT`、
`BLOCKED_TRANSACTION_LIMIT`、`BLOCKED_TRANSACTION_INCOMPLETE`、`BLOCKED_TRANSACTION_STRUCTURE`。
原因、日志、指标不包含原始 Key、value 或脚本；协议帧非法或 spool 损坏失败关闭。

## 验证与部署门禁

协议测试覆盖跨批次、字节 / 条数超限、孤立 EXEC、嵌套 MULTI、未闭合终止、范围混合、
SELECT、跨 Slot、内部 Key、spool/live 重叠及从提交边界重建。
Standalone / Cluster 执行器测试检查 EXEC 前无写入、一次完整提交、跨范围 / Slot 不路由。
真实 Redis 测试覆盖事务与 Lua effects 捕获、重建目标数据和已确认重复投递。

2026-09-17：contract、Platform、Worker 的隔离目录构建验证通过。Worker service 119 项测试，
0 失败 / 错误，6 项未启用的既有可选测试跳过；本阶段真实 Redis 6.2 / 7.4 effects 两项、
两类执行器六项、MySQL 策略领取两项均实际执行通过。协议测试覆盖新增读取与组装边界。
已有目标批次故障矩阵继续通过，没有因事务接入跳过 pending / fence 检查。

仍须完成生产发布门禁：持续高吞吐 / 多通道受限堆、真实主从切换、MOVED / ASK、
运行中暂停恢复和接管的整条任务链路测试。不得以单元测试或数据校验替代这些门禁。
未新增表或修改数据库 schema；已有 checkpoint / pending / fence 协议保持不变。
