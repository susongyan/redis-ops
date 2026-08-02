# Phase 2：采集、风险扫描与告警

状态：增强验收中。Collector、风险扫描和告警基础闭环已交付；同步联动告警和生产级验收仍在收口。

## 已实现

- Platform 内置 Collector 通过 `RedisConnectionProfileProvider` 读取 Redis `INFO`，在
  `/actuator/prometheus` 导出可用性、已采集节点数、内存、连接数、ops/s 与命中/未命中指标。
  Standalone 采集指定节点；Sentinel 先解析当前主节点；Cluster 读取各可用 master 后做集群级聚合。
  `GET /api/v1/collector/clusters/{clusterId}/nodes` 同时提供当前采样主节点的连接数、内存和
  Ops/s 明细；Prometheus 仍只使用 `cluster_id` 标签，避免节点高基数标签污染时序库。
- 风险扫描通过 `SCAN` 和现有只读检查通道识别大 Key 与无 TTL Key；Cluster 按当前 master
  分片扫描并持久化各分片 cursor，意外重试可从 checkpoint 恢复。结果按企业内部安全契约保存
  受控页面可见的原始 Key、类型、大小、TTL、节点与风险级别，不进入日志、指标、审计、异步
  payload 或外部通知；页面提供任务、进度、取消与分页结果。
- 告警规则按“规则 + 资源”收敛为 `OPEN → ACKNOWLEDGED → RESOLVED`。当前 Collector 不可用和
  风险扫描发现大 Key 会触发相应规则。
- Generic Webhook 通道的 URL 为 write-only 密文。投递异步落入 `notification_record`，失败采用
  有限指数退避，投递失败不回滚或改变告警状态。
- 告警支持设置静默截止时间；静默期间仍更新事件证据与最后触发时间，但不会新增 Webhook 投递。

## 当前边界

- Collector 当前以种子节点建立连接并采集基础 `INFO` 指标；Cluster 多主聚合和当前采样节点明细
  已实现。Sentinel 故障转移连续观测、低频 `LATENCY` 聚合与 MySQL 采集运行摘要仍是后续迭代。
- 大 Key 扫描当前仅是只读风险发现，支持任务级速率限制与取消，不会自动修复、删除或读取完整
  Value。Worker 异常后的 Job 租约接管会从已持久化的分片 cursor 继续。
- 首批规则类型为 `COLLECTOR_UNAVAILABLE`、`REDIS_MEMORY_HIGH`、`LARGE_KEY_FOUND`；同步 RPO、
  spool 水位与复制异常规则的采集器联动仍待补齐。
- Webhook 当前发送最小告警上下文；渠道编辑/停用、投递历史查询和有限指数重试已实现；签名
  Header、渠道级认证 Header 和告警规则完整联动仍待补齐。

## Prometheus 接入

Prometheus 抓取 Platform 的 `/actuator/prometheus`。标签仅包含稳定的 `cluster_id`，不包含 endpoint、
Redis Key、密码、clientName 或其它高基数字段。

管理前端新增“监控指标”页面（`#/metrics`），直接读取同一 Prometheus endpoint，按集群展示
Collector 可用性、节点数、内存、连接数、Ops/s、复制 backlog、Slowlog 和 Key 命中率，默认每 15 秒刷新；
完成采集的指标卡片会显示更新脉冲。页面仅展示观测数据，不提供 Redis 写操作。

开发环境可使用 `docker compose --profile observability up -d prometheus grafana` 启动 Prometheus
（`9090`）与 Grafana（`3000`）。Prometheus 模板抓取 Platform 与 Sync Worker 的 Actuator 指标；
Linux 主机需将 `host.docker.internal` 替换为实际部署地址。

## 风险操作约束

风险扫描使用 `SCAN`，禁止使用生产阻塞的 `KEYS`，也不执行 Redis 写命令。扫描输出用于人工
排查与后续治理 Dry Run，不可直接作为删除数据的指令。
