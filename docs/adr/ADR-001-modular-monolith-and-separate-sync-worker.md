# ADR-001：模块化单体控制面与独立 Sync Worker

- 状态：Accepted
- 日期：2026-07-29
- 关联契约：架构契约第 2 节

## 背景

平台首期需要快速交付资产管理和同步治理，但同步的数据复制有长连接、spool、本地磁盘与独立
资源消耗；将其放在 API 进程会放大 API 故障域。完全拆分所有治理领域又会过早引入服务发现、
跨服务认证和分布式一致性成本。

## 决策

Platform 保持模块化单体，承载 REST API、资产、任务控制、审计、Flyway 与轻量发现调度。
真实同步放入独立可执行 `governance-sync-service`。两者共享 MySQL，但拥有不同的职责与最小
数据库权限。

## 后果

- API/Worker 可以独立部署、扩容和重启，Worker 的 spool 使用本地持久盘。
- 当前仍存在共享库耦合；表访问权和端口抽象必须清晰，不能让 Worker 任意修改业务控制状态。
- Collector、Scan、Alert 等后续领域首先作为逻辑模块加入；只有满足独立拆分条件时再服务化。

## 替代方案与取舍

- 单一 API + Worker 进程：部署简单，但同步负载和 API 故障域耦合。
- 从首期起全微服务 + REST/MQ：边界更强，但凭证、回调、超时幂等和运维成本过高。

## 落地与验证

发布包应支持 Platform、Frontend、Worker 分角色部署；Sync Worker 不暴露业务控制 API，只暴露
Actuator/Prometheus。详见 [控制面与 Worker 分离](../sync-control-worker-separation.md)。
