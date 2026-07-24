# Redis 同步管理面与 Worker 分离方案

同步任务状态、控制命令、运行租约和故障接管的详细流程见
[Redis 同步 Worker 管理流程与生命周期](sync-worker-lifecycle.md)。

## 1. 背景与目标

Redis 同步包含两类职责：

- 管理面：任务配置、操作入口、审批/确认、主备关系、审计和页面展示。
- 数据面：PSYNC 长连接、RDB/命令流处理、目标写入、checkpoint、spool、指标和故障恢复。

数据面会长期占用网络、磁盘和内存，并且需要独立扩缩容，因此从第一版开始就使用独立的
`governance-sync-service` 进程和制品，不与 Platform API 合并运行。

需要单独决策的是：两个进程通过共享数据库协作，还是通过内部 REST API 协作。

## 2. 方案对比

| 对比项 | 共享 MySQL 租约队列 | 内部 REST API |
|---|---|---|
| 控制流 | Platform 写控制命令，Sync Service 通过租约领取 | Platform 调用 Sync Service 的 start/pause/resume/stop |
| 运行状态 | `sync_task/runtime/checkpoint/event` 保存在平台库 | Sync Service 维护独立运行库，Platform 轮询或接收回调 |
| Redis 密码 | Sync Service 读取集群秘密表并在本地解密，不经网络传递 | 仍需读取平台凭证库，或建设 mTLS 凭证下发/Broker |
| 故障恢复 | 控制命令持久化，Worker 换实例后按租约和目标 checkpoint 恢复 | 需要处理请求超时但任务已启动、重复请求和回调丢失 |
| Platform API 宕机 | 已运行任务可继续同步和写入指标 | 已运行任务可继续，但控制和状态回传依赖接口可用性 |
| MySQL 宕机 | 数据流可运行到租约安全边界，控制和事件暂停 | 独立运行库可继续；如果仍共享库则没有额外收益 |
| 权限边界 | Sync Service 需要受限数据库账号和同一主密钥 | 服务边界更清晰，但增加服务认证和凭证安全面 |
| 部署依赖 | 新 Jar、受限 DB 账号、持久卷 | 新 Jar、服务发现、mTLS、内部 API、运行库和回调 |
| 版本演进 | 通过端口抽象逐步替换为 REST/MQ | 从开始就需要管理跨服务契约兼容 |
| 适用阶段 | 单团队、单平台库、部署依赖少 | 多团队、跨网络域、大规模 Worker 集群 |

## 3. 当前落地：进程分离、共享 MySQL

首期采用以下边界：

- Platform 负责写 `sync_task`、控制 Job、主备关系和审计，不执行 Redis 数据复制。
- Sync Service 独立部署，只领取同步控制 Job，维护 runtime/channel/metric，并执行数据复制。
- Sync Service 不直接交换主备关系；它只把最终追平和停止结果写回同步任务，由 Platform
  的切换工作流完成关系变更。
- Sync Service 使用受限数据库账号：集群和秘密只读，同步运行、事件、指标和租约表读写。
- Redis 密码由 Sync Service 使用同一版本化密钥环在本地解密，任务 payload 只包含资源 ID
  和非秘密控制参数。
- Sync Service 必须持有独立数据目录，用于加密 spool；API 实例不挂载该目录。

共享数据库只是首期通信机制，不代表管理面和 Worker 合并部署。

## 4. 代码边界

为避免共享数据库固化成业务耦合，代码使用以下端口：

- `SyncCommandPort`：管理面提交 PRECHECK/START/PAUSE/RESUME/FINISH/CANCEL。
- `SyncCommandConsumer`：Worker 领取并确认控制命令。
- `SyncRuntimeRepository`：Worker 写运行租约、通道和低频指标。
- `SyncTaskStatePort`：Worker 上报状态，只有 Platform 工作流可以修改主备关系。
- `RedisConnectionProfileProvider`：Sync Service 按 clusterId 本地取得连接信息。

同步协议、RDB 解析、spool、checkpoint 和目标应用器不得直接依赖 Controller、MyBatis Mapper
或 HTTP DTO。

## 5. 后续完全分离目标

当出现跨网络域、多团队维护或大量 Worker 扩容需求时，演进为：

1. Sync Service 使用独立运行库，Platform 不再直接查询其内部表。
2. `SyncCommandPort` 替换为版本化内部 REST、gRPC 或消息队列。
3. 状态通过 Outbox 事件回传，Platform 只保存任务摘要和审计。
4. 两个服务使用 mTLS 和服务身份认证；所有控制请求继续保持幂等。
5. 建设凭证 Broker，或向 Worker 下发使用其服务身份加密的短期凭证信封；禁止普通 REST
   接口返回明文 Redis 密码。
6. checkpoint 的最终事实仍保存在目标 Redis，避免运行库切换导致非幂等命令重复执行。

## 6. 演进触发条件

满足任一条件时启动完全分离：

- Sync Worker 与 Platform 无法安全访问同一 MySQL。
- Worker 由独立团队发布，无法与数据库 migration 保持兼容升级。
- 单个数据库租约队列成为吞吐或可用性瓶颈。
- 需要跨地域部署大量 Worker，且数据库网络 RTT 影响租约稳定性。
- 已具备服务身份、mTLS、配置中心和凭证 Broker 等基础设施。

在触发条件出现前，共享 MySQL 能以更少依赖提供持久控制命令、租约接管和本地凭证解密，
同时保持管理进程与同步 Worker 的资源和部署隔离。
