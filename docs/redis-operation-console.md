# Redis 操作终端

Redis 操作终端使用数据库命令目录准入和 Lettuce 通用 RESP2 执行器。代码不维护命令名称白名单或黑名单；由 Redis 运维决定哪些命令可执行。它是单次请求执行器，不是持续连接的 redis-cli。

## 安全边界

- `operation_command_definition` 是唯一业务准入源；未配置或未启用即拒绝。Redis ACL 仍是服务端最终权限边界。
- DIRECT 提交即执行，CONFIRM 确认后执行，APPROVAL 审批后执行；不再根据 READ 属性绕过配置的审批。运维必须根据最危险的允许参数组合设置策略，不能将可写的子命令错误标为只读。
- SINGLE_KEY 通过配置的 Key 位置路由，Cluster 固定 DB 0。NO_KEY 仅支持 Standalone/Sentinel；Cluster 无 Key 请求需要明确节点目标，当前未实现。多 Key 路由、跨请求事务、持续订阅会话未实现，不通过名称黑名单模拟这些能力。
- 最多 128 个实参、32 个参数定义、1 MiB 参数总字节；VALUE 单项上限按目录配置（不超过 1 MiB）。单连接接收预算 1 MiB（包括握手）、解码内容 64 KiB、元素/声明容量 2048、嵌套 16 层。超限失败并关闭独立连接，不先读取无限结果再截断。
- 连接期限 2 秒，单条命令期限 3 秒，不自动重试。超时、断线或超限时 Redis 可能已执行，失败不等于撤销；应核查实际状态。TTL 是单 Key 的附加观测；无 Key 或观测失败时为 -1，不应据此判断持久性。
- 新操作不保存原始参数，仅保存摘要供执行时核对；不将 Key、Value 或凭据写入日志、审计或异步 payload。历史参数记录不自动清理。
- 受控内部页面允许显示 Redis Key 以便排障，但仍不在日志、指标和审计中记录敏感业务内容。

操作记录状态为 `PENDING_CONFIRMATION`、`PENDING_APPROVAL`、`APPROVED`、`EXECUTING`、`SUCCEEDED`、`FAILED` 或 `CANCELLED`。配置写入使用 `Idempotency-Key`，更新同时要求 `If-Match`。

## 命令策略配置

进入 `#/commandCatalog` 新增或编辑定义：命令名称、分类、读写属性、启用状态、风险、执行策略、参数 JSON、路由和 VALUE 大小限制。新增默认禁用，审核后再编辑启用。ADMIN 和 OPERATOR 均可维护，与既有运维权限一致。变更记录操作者快照与审计，原因必填。

例：扩充 `ECHO`，路由 NO_KEY、Key 位置 0、参数 JSON 为 `[{"name":"message","type":"TEXT","required":true}]`。`PING` 无参时使用 `[]`。`LRANGE` 使用 Key、INTEGER start、INTEGER stop 三项定义，Key 位置为 1。无需修改 Java 代码或重新部署。

类型支持 REDIS_KEY、TEXT、VALUE、INTEGER、DECIMAL；可选参数只能放在末尾，最后一项可以设置 `variadic: true`，`literal` 可锁定子命令或选项，例如 `{"name":"subcommand","type":"TEXT","required":true,"literal":"GET"}`。命令名是单个 token，子命令作为参数；不能把 `CONFIG GET` 作为命令名。

命令行支持单双引号和反斜杠转义下一字符。包含空格的 Value 必须加引号，例如 `SET key "hello world"`；不再把多余 token 自动拼入最后的 Value，也不会静默丢弃额外参数。

执行前重新核对目录启用状态及定义 ID/版本。停用或修改定义使旧待执行请求失效，需要重新创建并确认/审批。本版本以前未保存定义版本的待执行请求同样要求重建。业务记录状态的乐观锁在 Redis 调用前校验，但数据库与 Redis 之间不提供分布式事务；数据库提交失败应人工核查，不应盲目重试。

API：`POST /api/v1/operation-commands` 新增（幂等）；`PUT /api/v1/operation-commands/{id}/definition` 修改完整定义（幂等＋版本）；既有 GET 和策略更新接口保留。数据表已能容纳扩展，无需新增 migration；初始化包仍提供原有 15 条种子定义，不自动开放新命令。

本地 Compose Cluster 如果返回 Docker 内部节点地址，Platform 会按已配置的宿主机端口做演示环境地址映射；生产环境应让 Redis 正确配置 `cluster-announce` 地址，不依赖该本地兼容逻辑。
