# ADR-005：UTC 持久化时间语义

- 状态：Accepted
- 日期：2026-07-29
- 关联契约：架构契约第 3 节

## 背景

Platform 和 Sync Worker 使用 `Instant` 表达预检查有效期、租约、fence、checkpoint、指标和审计。
若 MySQL JDBC 会话使用业务时区，`DATETIME` 与 `Instant` 的双重转换可能导致预检查立即过期、
租约提前失效或页面时间错误。

## 决策

数据库连接统一使用 `serverTimezone=UTC`；数据库 `DATETIME(3)` 表示 UTC 时刻，应用层统一用
`Instant` 读写。页面再按浏览器时区展示。部署模板和 Platform/Worker 配置必须保持一致。

## 后果

- 任务时序与租约比较跨机房一致，不依赖服务器本地时区。
- 已存在的错误时区历史数据需要在升级时评估，不可在没有备份和迁移计划时直接批量改写。
- 任何新服务/脚本连接 MySQL 都必须沿用 UTC 会话设置。

## 落地与验证

验证预检查 10 分钟有效期、30 秒租约、跨进程读写 `Instant` 和 API ISO-8601 输出；部署文档的
JDBC 示例必须包含 `serverTimezone=UTC`。
