# 分析 Agent 配置与监控采集开关

以下均为 Platform 的 Spring 启动配置，可放在外部 application-pro.yml，后续也可由已接入的配置中心注入。
当前不支持运行中统一热更新；修改后受控重启 Platform。不要把配置中心发布成功当作已生效。

```yaml
redis-ops:
  analysis:
    http:
      endpoint: ""
    acp:
      endpoint: ""
collector:
  enabled: true
  fast-interval-ms: 15000
  page-size: 200
```

- 分析 API 始终可用，没有启用开关。配置 HTTP/ACP endpoint 后按请求调用，不自动分析。
- 同类型 HTTP 优先于 ACP；外部失败直接报错，不尝试其他 Provider。无匹配外部配置时使用明确标记的 RULE 分析。
- 模拟器不自动注册。注册资料不驱动运行时配置；分析写接口已支持幂等。
- 采集默认开启。enabled=false 时不创建采集调度 Bean，不连接 Redis 采集；快照 API 保留并返回空列表。
  本进程不再更新采集指标；数据库已有历史记录不会删除，Prometheus 保留的旧样本不代表实时采集。
- fast-interval-ms 是一轮 collect 完成到下一轮开始的固定延迟，不是每个 Redis 固定每 15 秒一次；大规模/慢连接会延长实际周期。
- page-size 是当前每轮查询的 ACTIVE 资产数量上限（现实现只查第一页），不是自动遍历所有资产的批量分页。
  两项数字必须大于 0，否则采集开启时启动失败。多 Platform 实例按角色配置开关，避免重复采集。
- 此开关只控制基础 Redis 监控采集，不关闭同步 Worker、风险扫描、校验或其他控制 Job。

旧分析 enabled 与 demo-enabled 属性不再生效，可从外部配置中心删除。采集开关不受此次调整影响。
Spring YAML 属性为主入口，环境变量并非必须。现有 AES-GCM 逻辑不变，密钥只从 redis-ops.credential.keys 读取，
默认示例密钥已移除，两个进程必须明确配置同一密钥环。

验收：分析接口无需启用开关，外部失败直接报错；采集分别以开关 true/false 启动，检查 Bean/接口注册、调度间隔、关闭后无新增采集记录；
不要只依据 /actuator/health 判断 Agent 或采集正在工作。
