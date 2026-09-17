# Redis 5.0 的 v3 事务同步

## 使用范围

使用 `sync-contract:0.3.1` 及本次对应 Platform / Worker，任务显式选择 v3。
源、目标支持 Redis 5.0 / 6.2 / 7.x；仍禁止高版本向低版本迁移，长期主备要求 major.minor 相同。
v1 默认不变，v2 仍要求 Redis 6.2 / 7.x。没有数据库迁移。

- 支持复制流完整 `MULTI ... EXEC`，包括 Lua 的具体写命令 effects。
- 保留整组过滤、同 Slot、事务容量、租约 / fence、pending 和确认 checkpoint 约束。
- 不执行原始 `EVAL/EVALSHA`；源端配置为脚本原文传播时，任务会阻塞而不是静默忽略。
- 不因支持 Redis 5 而使 Redis 5 获得 COPY、LMOVE 等较新命令；范围内命令仍须目标支持。
- 不自动修改源端 Lua 配置，不自动扩大旧任务的过滤范围或策略。

先升级所有 Worker，再使用 Redis 5 v3 任务。通过已有 `/actuator/info` 检查
`syncCapabilities.redis5Transactions=true`；仅有 `sourceTransactions=true` 不足以证明已升级。
若旧 Worker 与新 Worker 混用，旧实例仍可能领取 v3 然后因 Redis 5 版本门禁阻塞。

## 验证与复现

测试只在专用 Redis 中执行。下列 Standalone 测试内部有 `FLUSHALL`，不能指向业务实例。
在专用测试机预备 Redis 5.0.14：6390 为源、6391 为目标、6394 为脚本原文源；
全部绑定 127.0.0.1，6394 启动时设置 `--lua-replicate-commands no`，另外两个保持默认值。

```bash
REDIS_SYNC_IT=true REDIS_SYNC_LEGACY_LUA_IT=true SYNC_IT_POLICY_VERSION=v3 \
SYNC_TRANSACTION_TEST_REDIS5=127.0.0.1:6390 \
mvn -f redis-ops-sync-worker/pom.xml -pl sync-service -am \
  -Dtest=StandaloneSyncTaskRunnerIntegrationTest,StandaloneSyncTaskRunnerFactoryTest,SourceTransactionsRedisTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 脚本自行创建并清理具名隔离容器，测试端口需空闲
REDIS_SYNC_CLUSTER_IMAGE=redis:5.0.14 SYNC_IT_POLICY_VERSION=v3 \
  bash scripts/sync-cluster-it.sh
```

每个 Cluster 测试节点使用独立 `dbfilename`：同一容器运行多个 Redis 5 进程时，
磁盘式全量复制不能共用 `dump.rdb`。否则并发快照会产生与复制实现无关的数据混淆。
全量断言先等待全量完成，而不是用少量已出现的 Key 推断全量结束。

已覆盖 Standalone 的全量 / 增量、普通事务、Lua effects、包含与排除规则、
混合范围事务阻塞、暂停恢复、generation 接管后非幂等计数不重复；
脚本原文模式验证阻塞且目标没有部分写入。版本测试覆盖 v3 接纳 Redis 5、v2 拒绝 Redis 5、
预检查拒绝新版源到旧版目标，以及实际 INFO 版本门禁。

2026-09-17 验收：契约安装、Platform 与 Worker `mvn verify` 通过；上述 Redis 5 组合测试
14 项中执行 11 项、跳过 3 项（两个非 Redis 5 捕获用例及独立 ACL 用例），0 失败；
Redis 5 Cluster 三种拓扑测试 3 项通过，Redis 7.4 Cluster 同样 3 项回归通过。
前端 18 项测试及构建通过，1280 桌面 / 390 窄屏
已检查。前端安装仍有既有依赖风险与大 chunk 告警，本次未额外升级依赖。

生产剩余门禁仍包括主从切换、在线迁槽、持续负载和网络故障矩阵。
2026-09-17 按用户要求完成本地前端、Platform 与 Worker 重启：两后端健康状态为 UP，
Worker 返回 `redis5Transactions=true`。启动包经干净打包并核对包含契约 0.3.1；
现有配置、密钥和任务数据保留。该记录不代表生产部署或 Redis 5.0.4 已完成实测。

架构决策：[ADR-026](adr/ADR-026-redis5-source-transactions.md)。
