# 监控页面业务指标接口

状态：已实现。页面不再直接请求 `/actuator/prometheus`。

`GET /api/v1/collector/metrics` 沿用平台登录会话，ADMIN / OPERATOR 可读取；匿名返回 401，
强制改密及失效会话仍由统一鉴权处理。响应为标准 `{data, requestId}`，并设置 `Cache-Control: no-store`。

`data` 按集群 ID 分组，仅包含 Collector 已发布的十二种白名单数值（可用性、节点数、内存、连接数、
Ops/s、命中/未命中、命令次数/耗时、复制 backlog、Slowlog 数量）。内部 `_total` 指标在 JSON 字段中
移除该后缀以延续页面字段名；累计值含义不变。过滤非有限数值，不返回其他标签、JVM 指标或 Redis 原始 INFO。
采集器关闭或无快照时返回空对象，页面显示暂无数据，而非伪造健康状态。

接口只读取当前 Platform 进程内已采集快照，不触发 Redis 采集；不是历史时序查询或跨实例聚合。
节点详情继续使用 `/api/v1/collector/clusters/{clusterId}/nodes`。

前端通过统一 API 客户端读取 JSON，自动携带同源会话并处理 401。
正式部署只需现有 `/api/` 代理；不需要为浏览器放行 `/actuator/`。
Actuator 的默认拒绝策略保持不变，外部 Prometheus 接入仍需单独设计管理端点授权。
