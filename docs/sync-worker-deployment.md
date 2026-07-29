# Sync Worker 构建、部署与扩容

Sync Worker 是 `governance-sync-service` 生成的独立 Java 进程。它通过共享 MySQL 领取同步
控制命令、维护租约和运行状态，并在本机保存加密 spool；不依赖 Platform HTTP。

## 1. 构建和安装

发布包与 Platform 使用同一个构建入口：

```bash
./scripts/build-release.sh
```

把 `release/redis-ops-<version>.tar.gz` 复制到 Worker 机器，在一个空目录中解压：

```bash
mkdir -p /opt/redis-ops-worker
tar -xzf redis-ops-0.1.0.tar.gz -C /opt/redis-ops-worker --strip-components=1
cd /opt/redis-ops-worker
cp conf/redis-ops.env.example conf/redis-ops.env
chmod 600 conf/redis-ops.env
```

部署机需要 Linux、Bash、JDK 17+ 和 curl。仅部署 Worker 时不需要 Node.js、Maven 或 Nginx。

## 2. Worker 配置

必须配置：

```bash
DB_URL='jdbc:mysql://mysql.example.internal:3306/redis_governance?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC'
SYNC_DB_USERNAME='redis_ops_sync'
SYNC_DB_PASSWORD='...'
REDIS_OPS_CREDENTIAL_KEYS='v1:...'
SYNC_ENGINE_INSTANCE_ID='worker-10-20-30-41-sync-1'
SYNC_SERVICE_PORT=8081
SYNC_ENGINE_DATA_DIR='/data/redis-ops-sync'
```

关键约束：

- `REDIS_OPS_CREDENTIAL_KEYS` 必须与 Platform 完全一致，密码在 Worker 本地解密，不通过任务
  payload 或 Platform HTTP 传递。
- `SYNC_ENGINE_INSTANCE_ID` 必须在所有运行实例间唯一，建议包含机器 IP/主机名和实例序号。
- `SYNC_ENGINE_DATA_DIR` 必须位于容量充足、只有部署用户可访问的本地持久盘。
- `SYNC_FLYWAY_ENABLED=false` 保持默认值。数据库升级只由 Platform 执行。
- Worker Actuator 端口只应向监控系统和管理网开放，不对外提供业务控制 API。

建议为 Sync Worker 使用独立 MySQL 账号：集群资产和秘密表只读，同步任务、runtime、事件、
checkpoint 与指标相关表按运行需要授权读写。具体最小授权应随 migration 的表结构变更一同
维护和评审。

## 3. 启动、健康和停止

```bash
bin/redis-opsctl doctor worker
bin/redis-opsctl start worker
bin/redis-opsctl status worker
bin/redis-opsctl health worker
bin/redis-opsctl logs worker
```

健康地址默认为 `http://127.0.0.1:8081/actuator/health`。优雅停止：

```bash
bin/redis-opsctl stop worker
```

Worker 收到 SIGTERM 后由同步引擎撤销本地 Lease Guard、停止新写入并释放资源。若超过
`STOP_TIMEOUT_SECONDS` 仍未退出，脚本才发送 SIGKILL；任务后续按租约和 Redis Fence 接管。

systemd 部署：

```bash
bin/redis-opsctl install-systemd worker
sudo systemctl start redis-ops-worker
sudo systemctl status redis-ops-worker
```

## 4. 分机部署与扩容

Worker 机器只要能访问共享 MySQL、源 Redis 和目标 Redis，即可与 Platform 分机。它不需要
访问 Platform 的 8080 端口。扩容步骤：

1. 在新机器解压同一版本发布包。
2. 复制并保护配置，使用相同数据库地址和密钥环。
3. 设置新的 `SYNC_ENGINE_INSTANCE_ID` 和独占数据目录。
4. 执行 `doctor worker`，然后启动 Worker。
5. 在 Platform 页面确认新 worker ID、租约和心跳。

同一机器运行多个实例时，必须复制到不同发布目录，并分别设置：

- 不同的 `SYNC_ENGINE_INSTANCE_ID`
- 不同的 `SYNC_SERVICE_PORT`
- 不同的 `SYNC_ENGINE_DATA_DIR`
- 不同的 `var/run` 和日志目录（复制发布目录会自然隔离）

默认每实例最多运行两个同步任务，可通过 `SYNC_ENGINE_MAX_CONCURRENT_TASKS` 调整。扩容前应
同时评估 CPU、网络、目标 Redis 写能力和 spool 磁盘容量。

## 5. 升级和回滚

1. 先停止一个 Worker，确认其任务已停止写入或被安全接管。
2. 在新目录解压新版本，不覆盖旧目录。
3. 复制原配置并保持密钥环与 instance ID。
4. 执行 `doctor worker` 后启动新版本。
5. 检查 Actuator、运行实例、租约、generation、spool 和任务事件。
6. 分批升级其他 Worker。

若需回滚，先确认数据库 migration 与旧版本兼容，再停止新版本并从旧目录启动。不得让使用
同一个 instance ID 和数据目录的新旧进程同时运行。

## 6. 验收清单

- Worker Actuator 健康，日志中没有数据库密码、Redis 密码和密钥内容。
- 能从共享 MySQL 领取任务并按 5 秒周期续租。
- `SYNC_ENGINE_DATA_DIR` 可写，磁盘告警和容量限制符合部署预期。
- Platform 页面显示唯一 worker ID、运行阶段、generation、心跳和指标。
- 优雅停止后任务不再由旧 Worker 写入；接管时 generation 上升且目标 Fence 生效。
- Worker 跨机器接管仅使用目标 checkpoint、可用 backlog 或人工确认的新全量，不会自动清空目标。

更深入的运行和恢复操作参见 [同步服务部署与恢复手册](sync-operations-runbook.md) 与
[Worker 停顿、崩溃与安全接管](sync-worker-failure-takeover.md)。
