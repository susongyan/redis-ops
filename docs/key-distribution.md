# Key 前缀／业务归属分布分析

状态：首版已实现。属于 Platform 的按需分析领域，不依赖 Sync Worker，不自动周期扫描。
架构决策见 [ADR-017](adr/ADR-017-bounded-key-distribution.md)。

## 使用与统计含义

入口：采集与风险 → Key 分布分析。选择资产集群、DB，按需采样或手动输入示例，配置规则，确认预算后创建任务。
预览可跳过；修改规则在浏览器现有样本上重算，不再次扫描 Redis。正式任务始终从头扫描，不累计预览计数。

最多 32 条有序规则，首次匹配生效。固定业务分组按用户名称归类；前 N 段按字面分隔符提取，
N 为 1–8，分隔符为 1–8 个 UTF-8 字节。保留空段，不足段数、非法 UTF-8、超长 Key、超长分组分别进入系统桶。
规则 ID 是分组身份的一部分；系统桶与用户分组不同命名空间。固定分组名称和提取结果最多 256 字节。
系统不自动识别业务归属。选择过多段可能使分组文本等于整个 Key；因此需在预览中确认规则，避免把含业务标识的尾段作为分组。
数据库只保存有界聚合标签，不保存扫描得到的 Key 明细集合。

- 固定容量：已知固定分组预占位，新增动态分组满额后进入“分组超限”，已有分组继续计数。不是 Top-K。
- Top-K：Space-Saving，最多 1,000 个候选，默认分页展示 20 项。`count` 为估计上界，`count-error` 为下界。
- 系统桶精确统计本次接收的观测流，不竞争候选槽。未展示分组的合计不作为精确余数报告。
- SCAN 可能重复，数据也可能变化；即使所有游标归零，也不是精确独立 Key 数或一致性快照。
  占比以任务观测总次数为分母，算法误差不包含 SCAN 自身重复或非快照误差。
- 前缀匹配只影响分类，不意味着 Redis 能跳过其他 Key；完整遍历仍是 O(N)。

## 内存与连接边界

RESP2 接收器只允许认证、DB 选择、主节点/拓扑发现及 SCAN 命令，不读取 value、逐 Key MEMORY/TTL 或热度。
在分配前检查长度，逐项接收；不按服务器声明的数组长度预分配集合，不用 Pipeline 或预取队列。
超限响应整页丢弃，不推进游标或累计部分结果。超过单 Key 限额的数据流式丢弃，计入异常桶。
响应长度、递归深度、元素数和命令期限都有上限；期限到达或执行线程中断会关闭连接。
现有连接模型不支持的安全协议不会被隐式降级为明文；凭据仍通过既有可清零连接配置入口读取。

预览默认最多 1,000 次观测、256 KiB 样本、15 秒；只保存在当前页面内存，五分钟、切换集群/DB或离开页面时清除。
响应 `Cache-Control: no-store`。手动示例不发送后端。部署网关不得缓存预览、记录请求/响应体；不要启用包含 SQL 参数的调试日志。

功能内存不随全库 Key 数量或真实分组基数无限增长，但不承诺共用 JVM 其他模块不 OOM，也不承诺 Redis 无负载影响。
两百万不同前缀的合成 RESP→归类→Top-K→编码测试在 `-Xmx64m` 下通过，1,000 个计数器，GC 后存活堆约 5 MiB。
该结果不是生产 JVM 总内存容量建议，生产须在自身负载下试扫。

## Spring 配置

默认值在 Platform `bootstrap/src/main/resources/application.yml`。外部 `config/application.yml` 或配置中心可覆盖；
所有数字受代码安全上限校验，不能通过配置放大安全上限。需要更严格的环境可收紧：

```yaml
distribution:
  concurrent-tasks: 1           # 1–2，预览共用
  max-duration-seconds: 21600   # 任务页面默认 1800；这里是部署上限
  max-keys-per-second: 1000
  max-scans-per-second: 5
  max-groups: 1000
  scan-count: 200              # 提示值，不是响应硬上限
  max-response-bytes: 1048576
  max-response-elements: 5000
  max-key-bytes: 4096
  max-shards: 256
  connect-timeout-ms: 2000
  command-timeout-ms: 3000
```

checkpoint 编码硬上限 2 MiB；API 请求体硬上限 64 KiB；规则和任务预算校验独立于页面。
默认任务最多 100 万次观测、30 分钟；`maxObservations=0` 表示完整遍历，但仍受最长时间限制。
同集群在整个 MySQL 控制域内互斥，不同集群可由多个 Platform 分别执行；部署多个进程时应同时评估总体扫描负载。
并发/预算属性在进程启动时绑定，不承诺 Apollo 修改后自动热更新；沿用 [Apollo 集成文档](apollo-integration.md)，配置变更后滚动重启。
本功能不引入配置中心 SDK、不改密钥 AES-GCM 逻辑。

## API

统一前缀 `/api/v1/key-distributions`，响应沿用平台 envelope。

| 接口 | 参数与要求 |
|---|---|
| POST `/preview` | `{clusterId,database}`；不走通用幂等存储；同集群预览至少间隔 15 秒 |
| POST `/tasks` | 以下任务配置；要求 `Idempotency-Key` |
| GET `/tasks` | `page=1&size=20`，最大 size=100 |
| GET `/tasks/{id}` | 状态、不可变配置、观测数、完成分片、执行时间、版本和固定原因码 |
| GET `/tasks/{id}/groups` | 同样分页；返回分组身份、`count`、`error` |
| POST `/tasks/{id}/pause`、`resume`、`cancel` | `If-Match` 使用最新任务 version，并要求 `Idempotency-Key` |

```json
{
  "clusterId": 1, "database": 0,
  "rules": [{"id":"order","name":"订单","prefix":"order:","kind":"SEGMENTS","delimiter":":","segments":2}],
  "mode":"TOP_K", "capacity":1000,
  "maxObservations":1000000, "durationSeconds":1800, "keysPerSecond":1000
}
```

`kind` 可为 `FIXED` 或 `SEGMENTS`；`mode` 可为 `FIXED` 或 `TOP_K`。创建后规则不可变，改变规则需新建任务。
运行进度提交也递增 version，控制冲突时刷新后重试，不移除 `If-Match`。
UI/API 原因码：`BUDGET_REACHED`、`TOPOLOGY_CHANGED`、`SCAN_*_LIMIT`、`SCAN_TIMEOUT`、`SCAN_SLOW` 等；
不会携带 Redis 响应内容、原始样本或凭据。

## 数据、恢复与运维

V30 新增 `key_distribution_task`、`key_distribution_runtime`、`key_distribution_group`，不修改同步表。
任务表保存不可变配置、进度及有界 checkpoint；运行表按集群保存唯一执行槽、owner/generation/lease；结果表保存有界聚合行。
checkpoint 包含游标和聚合状态。每约 1 秒原子提交并重建最多 1,006 条结果行（候选加系统桶），需评估 MySQL 写入预算。
当前保守实现每批重新建立当前分片连接，不同时保持全部分片连接。

领取租约默认 30 秒、每 5 秒续租。保存校验 owner、generation 和有效租约；失败立即停止扫描，不继续缓存。
崩溃从最后已提交游标和聚合状态一起恢复，未提交页重扫；只能避免应用重试叠加已提交批次，不能去除 SCAN 自身重复。
暂停保留最后提交状态并释放配额，恢复重新核对拓扑。取消不删除结果。数据库故障无法报告状态时保留最后数据库事实，
租约过期后再接管，不伪造成功。

Cluster DB 固定 0，只扫描主节点，核对完整 slot 覆盖和迁移标记；Standalone/Sentinel 支持 DB 0–15 并验证当前主节点。
扫描前、运行中约每 5 秒、结束时核对拓扑；变化则“不完整”，不偷偷重扫混合统计。
连续三次批次往返超过 200 ms 降低目标速率，连续六次暂停；三次连续超时暂停。它是扫描延迟保护，不是业务 P99 监控。
终态任务默认保留 30 天，每小时最多清理 20 个并级联删除结果；排队/暂停任务不自动删除。
此调度独立于 `worker.enabled` 的资产发现/通用 Job 开关；该开关不会禁用 Key 分布任务或预览。

新环境交给 DBA 执行 [最新完整 SQL](../redis-ops-platform/sql/latest/redis-governance-init.sql)。已有 V29 环境由 Platform Flyway 执行 V30；
不要把最新初始化包导入已有库。回退应用不删除 V30 表，先暂停/取消分析任务，保留表和结果待后续版本接管。

## 验收命令

```bash
mvn -f redis-ops-platform/pom.xml spotless:apply verify
cd redis-ops-frontend
npm ci
node --test src/distributionRules.test.js
npm run build
cd ..
node scripts/generate-database-baseline.mjs
node scripts/verify-key-distribution.mjs
```

集成脚本只创建独立临时 MySQL 和 Redis 容器，包含 Standalone、Sentinel 和三主分片 Cluster，执行结束清理这些容器及临时卷。
可通过 `DISTRIBUTION_TEST_REDIS_IMAGE` 指定测试镜像（默认 `redis:7.4-alpine`）。
验证包括 Platform 88 项测试、前端规则测试与构建、V1–V30 迁移链及完整 SQL 回灌，
以及真实 API 页面创建、低预算不完整结果、桌面和 390px 窄屏检查。
集成脚本覆盖 MySQL 租约竞争、预览互斥、原子回滚、连接中断、旧 generation、控制操作、
三种模式正常遍历、主节点角色变化、Cluster 迁移标记和 64 MiB 堆高基数持续接收。
慢响应/主动取消由本地 Socket 测试覆盖；生产规模 Sentinel 自动选举、跨机网络分区和与业务负载并发的长期性能需部署环境 UAT 验证。
生产先用较小观测/时长预算试扫，不自动开启周期性全量分析。
