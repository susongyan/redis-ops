# ADR-008：Phase 2 内置 Collector 与 Prometheus 指标边界

- 状态：Accepted
- 日期：2026-07-31

Platform Collector 通过 `RedisConnectionProfileProvider` 获取连接参数并采集 Redis 指标，避免为独立 exporter 再分发 Redis 密码。高频指标由 Platform/Sync Worker 的 Prometheus endpoint 暴露，Prometheus/Grafana 由外部基础设施部署；MySQL 只保存运行摘要、任务和告警状态。

Collector、风险扫描和告警先作为 Platform 内逻辑模块运行，不增加独立服务。风险扫描只读，结果不保存原始 Key 或 Value。通知渠道的秘密配置必须使用应用层加密，并且不通过 API、审计或日志返回。
