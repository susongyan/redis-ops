# Redis 操作终端

Redis 操作终端提供受控、结构化的单 Key 运维操作。页面只显示命令目录允许的参数，不提供任意 CLI、RESP、Lua 或批量脚本入口。

## 安全边界

- 命令目录存储在 `operation_command_definition`，包含参数 schema、路由策略、风险等级和审批策略；新增简单命令优先通过目录与适配器能力扩展。
- GET/TTL/TYPE/EXISTS 等只读命令直接执行；低风险写命令需要二次确认；中高风险和删除命令需要审批。
- 集群模式固定 DB 0，所有命令必须是单 Key 路由；FLUSHALL、FLUSHDB、EVAL、任意多 Key 和事务命令不在目录中。
- Value 输入限制 4 KiB。结果仅返回截断文本、类型、长度、TTL 和错误码；密码、密文和完整 Value 不写入日志、审计或异步 payload。
- 受控内部页面允许显示 Redis Key 以便排障，但仍不在日志、指标和审计中记录敏感业务内容。

操作记录状态为 `PENDING_CONFIRMATION`、`PENDING_APPROVAL`、`APPROVED`、`EXECUTING`、`SUCCEEDED`、`FAILED` 或 `CANCELLED`，每个写接口使用 `Idempotency-Key` 和 `If-Match`。

## 命令策略配置

进入 `#/commandCatalog` 可以配置命令目录：启用状态、风险等级、确认策略、允许的数据类型、Key 不存在策略和 Value 字节上限。命令策略为全局配置，保存时使用版本号校验并记录操作者和变更原因。

Redis Console 执行前会调用 `TYPE` 校验目标 Key。类型不匹配返回 `KEY_TYPE_NOT_ALLOWED`；需要已有 Key 但目标不存在时返回 `MISSING_KEY_NOT_ALLOWED`。危险命令只有在 HIGH + APPROVAL 且填写变更原因后才能启用，并且仍受执行器白名单限制。

本地 Compose Cluster 如果返回 Docker 内部节点地址，Platform 会按已配置的宿主机端口做演示环境地址映射；生产环境应让 Redis 正确配置 `cluster-announce` 地址，不依赖该本地兼容逻辑。
