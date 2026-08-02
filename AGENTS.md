# Redis Governance Platform 协作约束

本文件约束在此仓库中工作的开发 Agent 与人工贡献者。详细架构边界见
[`docs/architecture-contract.md`](docs/architecture-contract.md)，架构决策见
[`docs/adr/README.md`](docs/adr/README.md)。

## 1. 交付原则

1. 先确认改动属于资产、同步、采集、风险、治理、告警或分析中的哪个领域；跨领域改动必须
   说明数据流和失败边界。
2. 不把未来能力伪装成已实现能力。文档、页面、API 和告警文案应明确 `已实现`、`进行中`
   或 `规划中`。
3. 生产 Redis 的修改性动作必须经过预检查、明确确认、限速、可暂停/恢复和审计；不得新增
   静默清空、删除或自动流量切换。
4. 不记录、返回、打印或写入审计 Redis 密码、密文、主密钥或完整 Redis value。企业内部排障
   页面可受控展示 Redis Key；Key 不得进入日志、指标、审计、异步 payload 或对外通知。
5. 任何新表、状态、事件或跨模块调用都应先检查架构契约；若改变既有约束，先新增 ADR。

## 2. 模块依赖与代码位置

依赖方向只能从外向内：

```text
bootstrap / api / infrastructure → application → domain → common
sync-service → sync-protocol、application/domain/infrastructure 的公开端口
frontend → REST API
```

- `governance-domain`：领域模型、状态机、端口；不得依赖 Spring、MyBatis、Redis 客户端。
- `governance-application`：用例编排、事务边界、校验和审计触发；不得直接拼 SQL 或持有 Redis
  客户端。
- `governance-infrastructure`：MySQL、Redis、加密、外部适配器实现。
- `governance-api`：HTTP 请求/响应、参数校验和错误映射；不得承载业务状态机。
- `governance-bootstrap`：Platform 进程、Flyway、API 和轻量发现调度。
- `governance-sync-protocol`：RESP、PSYNC、RDB、命令规划；必须保持无 Spring 运行时依赖。
- `governance-sync-service`：独立 Sync Worker，读取控制命令并执行数据面同步；不得修改主备关系
  或绕过 Platform 的确认流程。
- `frontend`：React + Ant Design；所有写请求必须通过统一 API 客户端发送幂等键和版本。

新增模块前，先在 ADR 中说明：所有权、部署形态、数据所有权、失败恢复和迁移路径。

## 3. 数据、状态与安全

- MySQL 事务状态使用 UTC `Instant` 语义；JDBC 连接必须使用 `serverTimezone=UTC`。
- 新 schema 只能通过递增 Flyway migration 新增；已发布 migration 不得修改或重编号。
- 核心可变资源使用 `version` 乐观锁；HTTP 写接口使用 `If-Match` 和 `Idempotency-Key`。
- 同步任务的控制意图由 Platform 写入；Sync Worker 通过租约领取。目标 Redis checkpoint 和
  fence 是数据写入的最终事实。
- 异步 payload 只保存 ID 与非秘密业务参数，不保存密码、密文、完整 key/value 或连接串。
- 同步全量进度是观测数据，写入失败不得破坏同步安全；checkpoint、fence 和 lease 失败必须
  失败关闭。

## 4. 实现与验证

- Java 使用 Spotless：`mvn spotless:apply`，提交前至少运行 `mvn verify`（或说明受限模块）。
- 前端至少运行：`cd frontend && npm run build`。
- 修改 Flyway、Mapper、状态机、租约、fence、密码处理或命令策略时，必须增加/更新相应测试。
- 修改页面时至少检查窄屏（小于 800px）与桌面布局；页面不得只依赖隐藏侧栏导航。
- 对真实 Redis 同步、清空目标 DB、数据填充等演示性动作，先说明目标和影响范围，再执行。

## 5. 文档与审查

- 新 API、持久化模型或状态迁移要同步更新对应 `docs/` 文档。
- 架构取舍、不可逆存储决策、服务拆分和安全模型变化必须新增 ADR，不要只散落在提交说明中。
- 代码审查使用 [`agents/review-agent.md`](agents/review-agent.md) 的检查清单；审查只报告有
  明确证据和可操作修复建议的问题。
