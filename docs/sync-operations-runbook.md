# Redis 同步服务部署、恢复与验收手册

## 1. 部署边界

Platform 与 Sync Service 分别运行。两者共享 MySQL，但使用不同数据库账号：

- Platform：业务表、控制命令和审计读写。
- Sync Service：资产和集群秘密只读；同步 runtime、channel、metric、event 和控制 Job 读写。

Sync Service 必须持久挂载 `SYNC_ENGINE_DATA_DIR`。`REDIS_OPS_CREDENTIAL_KEYS` 在 Platform 与
Sync Service 中使用同一密钥环，首个 Key 是当前写入 Key；不得写入镜像、仓库或日志。

上线前至少设置：

```text
SYNC_ENGINE_DATA_DIR=/var/lib/redis-ops-sync
SYNC_ENGINE_MAX_CONCURRENT_TASKS=2
SYNC_ENGINE_LEASE_RENEW_INTERVAL_MS=5000
SYNC_ENGINE_METRIC_INTERVAL_MS=1000
REDIS_OPS_CREDENTIAL_KEYS=v2:<base64-32-byte>,v1:<base64-32-byte>
```

## 2. 发布与回滚

1. 先备份 MySQL，并确认 Flyway migration 版本。
2. 暂停领取新的 START/PRECHECK 控制命令。
3. 等待正在运行的 Worker 完成续租，逐实例滚动发布。
4. 每次只停止一个实例；运行任务等待租约过期后由其他实例以更高 generation 接管。
5. 检查 `/actuator/health/readiness`、租约续期、目标 fence generation 和任务事件。

数据库 migration 只允许向前修复，不直接回滚已执行脚本。应用回滚前必须确认旧版本能够读取
当前 schema；不兼容时保持新版本并发布修复 migration。

## 3. 故障处置

### Worker 崩溃或长时间 GC

- 不手工修改 `lease_owner` 或 `fencing_generation`。
- 等待 30 秒租约自然过期，新 Worker 会先发布目标 Redis Fence 再恢复。
- 同机优先读取加密 spool；跨机从目标 checkpoint 执行 PSYNC。
- backlog 不足时任务进入 `BLOCKED_REQUIRES_FULL_RESYNC`，必须重新执行预检查、写隔离和清空确认。

### MySQL 中断

- Worker 到达本地租约安全截止时间后停止源读取、ACK 和目标写入。
- MySQL 恢复后先检查 runtime、目标 fence 和 checkpoint，再允许自动接管。
- 不通过延长 JVM 本地超时绕过控制面。

### Spool 磁盘告警

- 70%：告警并降低源端或提高目标吞吐。
- 90%：Worker 停止源读取，不再 ACK。
- 磁盘满或 GCM/CRC 校验失败：保留现场，禁止删除 checkpoint 后强行恢复。

### Sentinel failover

- 事件应出现 `SOURCE_MASTER_CHANGED` 或 `TARGET_MASTER_CHANGED`。
- 源端要求新 master 对原 replId/offset 返回 CONTINUE；否则进入重新全量阻塞。
- 目标端重连后以目标 checkpoint 判断旧事务是否已提交，不依据 MySQL offset 猜测。

## 4. 上线前验收

Java、格式与前端：

```bash
mvn clean verify
cd frontend && npm ci && npm run build
```

Redis 版本矩阵：

```bash
./scripts/sync-version-matrix.sh
```

Cluster 三种拓扑转换：

```bash
./scripts/sync-cluster-it.sh
```

该脚本启动两套三主节点 Cluster 和一套双 Standalone 测试环境，验证
Cluster→Cluster、Standalone→Cluster、Cluster→Standalone 的全量与增量；同时覆盖跨 Slot
安全命令拆分、暂停恢复和新 generation 从目标 cursor/checkpoint 接管。

必须另行完成：

- Sentinel 旧主降级、新主提升和 PSYNC 续传演练。
- Cluster 多版本、master failover 和在线 reshard 的失败关闭/恢复演练。
- Worker 在 `WATCH` 后、`EXEC` 后、spool fsync 后的崩溃注入。
- MySQL 中断超过租约安全窗口、磁盘满和双 Worker 竞争。
- 100 GB 全量与持续 5 万 ops/s 基准；记录吞吐、RPO、ETA、CPU、GC 和 spool 趋势。
- 正向同步、外部流量切换、反向全量的完整主备演练。

任何验收失败都不得通过手工改任务状态、删除平台保留 Key 或跳过目标清空确认来继续。
