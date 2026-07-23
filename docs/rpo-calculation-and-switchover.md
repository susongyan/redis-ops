# Redis 同步 RPO 计算与切换判定

本文用于后续接入 RedisShake 或其他同步工具时，统一 RPO、Offset、Backlog 和追平时间的定义与计算方式。当前平台的 `lastRpoSeconds` 仍由状态推进接口提交，尚未实现自动采集；后续同步适配器应按照本文模型上报。

## 1. 指标定义

| 指标 | 含义 | 单位 | 是否可直接作为 RPO |
|---|---|---:|---|
| `desiredRpoSeconds` | 主备关系允许的最大数据落后时间 | 秒 | 目标值 |
| `timestampLagSeconds` | 目标端最新源端数据时间与当前时间的差值 | 秒 | 是，推荐 |
| `sourceOffset` | 源端已经产生的数据流位置 | 字节位置 | 否 |
| `targetOffset` | 目标端已经应用的数据流位置 | 字节位置 | 否 |
| `offsetGapBytes` | `sourceOffset - targetOffset` | 字节 | 否 |
| `estimatedLagSeconds` | Offset 差值结合源端写入速率得到的估算延迟 | 秒 | 仅作为估算 |
| `backlogBytes` | 同步工具尚未应用到目标端的数据量 | 字节 | 否 |
| `catchUpEtaSeconds` | 按当前净追赶速度预计还需多久追平 | 秒 | 否，属于 ETA |
| `metricCollectedAt` | 本次指标采集时间 | 时间戳 | 用于判断指标是否过期 |

RPO 表示故障切换时可能丢失的业务数据时间范围。Offset 和 Backlog 表示数据量或位置，必须结合时间水位或吞吐速率才能换算为时间。

## 2. 时间戳水位法

时间戳水位法最接近真实 RPO，建议作为切换判断的主要依据。

源集群周期性写入一个保留 Key，写入内容至少包含源端时间和递增序号：

```text
Key:   {redis_ops_sync}:heartbeat
Value: {"sourceUnixMs":1753236000000,"sequence":1024}
```

该 Key 必须经过真实同步链路到达目标集群，不能由目标端 Worker 自行写入，也不能被同步过滤规则排除。Cluster 模式使用固定 Hash Tag，避免不同心跳字段落入不同 Slot。建议覆盖写入且不设置 TTL，使同步中断后仍能读取最后水位。

目标端读取到心跳后计算：

```text
timestampLagSeconds =
    max(0, targetObservedUnixMs - sourceUnixMs) / 1000
```

例如目标端当前时间为 `10:00:00`，读取到的最新源端心跳为 `09:59:54`，则时间戳延迟约为 6 秒。

该值包含：

- 心跳写入间隔。
- 同步工具读取、传输和应用延迟。
- 源端与目标端的时钟偏差。

因此源、目标和 Worker 必须启用 NTP/Chrony。若任一节点时钟偏差超过允许值，应把 RPO 标记为不可信并阻止切换，而不是把负延迟强制视为正常。

## 3. Offset 延迟估算

Redis replication offset 或同步工具 offset 通常按复制流字节位置增长：

```text
offsetGapBytes = max(0, sourceOffset - targetOffset)
```

最近窗口内的源端写入速率：

```text
sourceBytesPerSecond =
    (sourceOffsetNow - sourceOffsetWindowStart) / windowSeconds
```

估算时间延迟：

```text
estimatedLagSeconds =
    offsetGapBytes / sourceBytesPerSecond
```

示例：

```text
Offset 差值：120 MB
最近 60 秒源端平均写入速率：20 MB/s
估算延迟：120 / 20 = 6 秒
```

写入速率应使用 30～60 秒滑动窗口或 EWMA 平滑，不能直接使用瞬时值。Offset 估算存在以下限制：

- 突发流量会让换算结果快速波动。
- 大 Key 会显著增加字节量，但不等价于更多业务记录。
- 源端低流量或停止写入时，分母接近 0，无法可靠换算。
- 不同同步工具的 Offset 语义可能不同，适配器必须先确认是否为同一数据流位置。

因此 Offset 换算结果必须标记为 `ESTIMATED`，不能与时间戳水位得到的实测 RPO 混为一谈。

## 4. Backlog 与追平时间

Backlog 更适合计算预计追平时间，而不是计算 RPO。

源端停止写入时：

```text
catchUpEtaSeconds =
    backlogBytes / targetApplyBytesPerSecond
```

源端持续写入时：

```text
netCatchUpBytesPerSecond =
    targetApplyBytesPerSecond - sourceWriteBytesPerSecond

catchUpEtaSeconds =
    backlogBytes / netCatchUpBytesPerSecond
```

示例：

```text
Backlog：600 MB
源端写入速度：20 MB/s
目标端应用速度：50 MB/s
净追赶速度：30 MB/s
预计追平时间：600 / 30 = 20 秒
```

若目标应用速度小于或等于源端写入速度，积压不会收敛：

```text
targetApplyBytesPerSecond <= sourceWriteBytesPerSecond
```

此时应将同步状态标记为“无法追平”或“持续积压”，触发告警并禁止切换，不应返回一个有限 ETA。

## 5. 指标优先级与可信度

建议按以下优先级确定平台展示和切换使用的 RPO：

1. `TIMESTAMP_WATERMARK`：使用同步心跳时间戳，作为主要实测 RPO。
2. `SOURCE_EVENT_TIMESTAMP`：同步工具能够提供最后应用源事件时间时直接使用。
3. `OFFSET_RATE_ESTIMATE`：没有时间戳指标时，以 Offset 和滑动写入速率估算。
4. `UNAVAILABLE`：指标过期、速率过低、时钟异常或 Offset 语义不一致时，不生成 RPO。

建议每次采集同时记录：

```json
{
  "relationId": 1,
  "taskId": 10,
  "timestampLagSeconds": 6,
  "estimatedLagSeconds": 7,
  "offsetGapBytes": 125829120,
  "backlogBytes": 125829120,
  "sourceBytesPerSecond": 20971520,
  "targetApplyBytesPerSecond": 52428800,
  "catchUpEtaSeconds": 4,
  "calculationMethod": "TIMESTAMP_WATERMARK",
  "confidence": "HIGH",
  "metricCollectedAt": "2026-07-23T10:00:00Z"
}
```

API、数据库和监控指标应保留 `calculationMethod` 与采集时间，页面需要明确显示“实测 RPO”或“估算 RPO”。

## 6. 主备切换判定

发起切换前不能只判断单次 RPO 数值。建议同步适配器和切换工作流依次检查：

1. 源、目标集群节点健康，连接和认证正常。
2. 同步任务处于增量同步阶段，没有同步错误。
3. RPO 指标未过期，采集链路正常。
4. `timestampLagSeconds <= desiredRpoSeconds`；没有时间戳指标时才允许使用高可信的估算值。
5. 连续多个采样周期满足目标，避免瞬时抖动导致误切换。
6. Offset Gap 和 Backlog 稳定下降或已接近零。
7. 目标端应用速度能够覆盖源端写入速度。
8. 停止旧方向同步前执行最后一次采样；停止后再次确认最终水位。
9. 进入 `WAITING_EXTERNAL_SWITCH` 后，不再自动交换主备，等待外部业务流量切换完成并由操作人确认。

任一关键指标不可用、过期或不可信时，应阻止切换并展示具体原因。不能把“没有采集到 RPO”当成“RPO 为 0”。

## 7. 平台落地建议

后续同步模块可分阶段实现：

1. 同步适配器上报源/目标 Offset、Backlog、吞吐和采集时间。
2. 增加心跳水位写入与目标端读取，形成实测时间延迟。
3. 增加 RPO 样本表或时序指标，保留最近趋势而不是只保存 `lastRpoSeconds`。
4. 页面展示目标 RPO、当前 RPO、计算方式、指标新鲜度、Backlog 和追平 ETA。
5. 增加连续达标、指标过期、无法追平和时钟异常告警。
6. 将受控切换前置检查改为读取最近一段时间的样本，替换当前手工填写 `lastRpoSeconds` 的方式。

当前阶段的 `desiredRpoSeconds` 已用于 `CAUGHT_UP` 状态和主备切换校验，但 `lastRpoSeconds` 仍是人工输入值，只能视为流程占位，不能视为平台已经自动保证 RPO。
