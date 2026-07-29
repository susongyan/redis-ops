# Redis Governance Platform

Redis 轻量级运维治理平台。Phase 1 已完成 Redis 资产管理，并正在实现自研同步数据面：
Standalone 全量/增量闭环已经可运行，Sentinel 单通道与 failover 重连代码已经完成，
Cluster 多 master 通道、目标 Slot 路由和安全接管已经形成闭环。RBAC 暂不实现。

## 工程结构

```text
redis-governance-platform
├── governance-common          # 通用类型、错误码和基础工具
├── governance-domain          # 领域模型、状态机和仓储接口
├── governance-application     # 用例编排、命令/查询服务
├── governance-infrastructure  # MyBatis、Redis/同步工具适配器
├── governance-api             # REST API、DTO、统一异常处理
├── governance-bootstrap       # API 与异步任务单进程启动入口
├── governance-sync-protocol   # 自研 RESP、PSYNC、RDB 和命令规划
├── governance-sync-service    # 独立同步 Worker、租约、spool、checkpoint 和指标
├── docs                       # 架构、ER 与任务拆分
└── sql                        # 数据库 migration
```

## 本地构建

要求 JDK 17、Maven 3.9+、Node.js 20+ 和 Docker。

```bash
mvn verify
cd frontend && npm install && npm run build
```

## Linux 快速部署

生成包含 Platform、Sync Worker、前端静态文件、Nginx/systemd 模板和统一控制脚本的自包含
发布包：

```bash
./scripts/build-release.sh
```

解压后复制并保护配置，再按角色启动：

```bash
cp conf/redis-ops.env.example conf/redis-ops.env
chmod 600 conf/redis-ops.env
bin/redis-opsctl doctor all
bin/redis-opsctl start all
```

支持 `platform`、`frontend`、`worker` 分角色部署，也支持可选 systemd 安装。完整说明见
[Platform 前后端构建与部署](docs/platform-deployment.md) 和
[Sync Worker 构建、部署与扩容](docs/sync-worker-deployment.md)。

## 本地运行

```bash
docker compose up -d mysql redis
export REDIS_OPS_CREDENTIAL_KEYS="v1:$(openssl rand -base64 32)"
mvn -pl governance-bootstrap -am spring-boot:run
cd frontend && npm run dev
```

默认同一进程同时提供 API 并领取异步任务；设置 `WORKER_ENABLED=false` 可启动纯 API 实例。`REDIS_OPS_CREDENTIAL_KEYS` 的第一个 Key 用于新写入，后续 Key 仅用于读取和在线轮换旧密文。密钥只在首次部署时生成，后续重启必须复用同一密钥；生产环境应由部署系统安全注入，不能每次启动重新生成。

API、内置 Worker 和 Redis 的端到端资产验收：

```bash
mvn package
./scripts/asset-smoke.sh
```

同步引擎的 Redis 版本矩阵和 Cluster 拓扑转换验收：

```bash
./scripts/sync-version-matrix.sh
./scripts/sync-cluster-it.sh
```

验收脚本会启动无认证 Standalone、ACL Standalone、Sentinel 和三主节点 Cluster，验证连通性、
异步拓扑发现、失败快照保留、幂等、审计及秘密脱敏。只启动这些 Redis 测试实例可执行：

```bash
./scripts/redis-asset-test-up.sh
```

详细设计见 [架构设计](docs/architecture.md)、[跨机房关系与同步](docs/cross-idc-sync.md)、
[架构契约](docs/architecture-contract.md)、[架构决策记录](docs/adr/README.md)、
[同步管理面与 Worker 分离](docs/sync-control-worker-separation.md)、
[同步 Worker 管理流程与生命周期](docs/sync-worker-lifecycle.md)、
[同步服务部署与恢复手册](docs/sync-operations-runbook.md)、
[Platform 前后端构建与部署](docs/platform-deployment.md)、
[Sync Worker 构建、部署与扩容](docs/sync-worker-deployment.md)、
[RPO 计算与切换判定](docs/rpo-calculation-and-switchover.md) 和
[Phase 1 任务拆分](docs/phase1-tasks.md)。

Redis 资产模块的启动方式和接口见 [Asset Management API](docs/asset-management-api.md)。

## Java 代码格式

项目使用 Spotless 调用 Eclipse Formatter，统一采用 4 空格缩进和 120 字符行宽：

```bash
mvn spotless:apply
mvn spotless:check
```

`mvn verify` 会自动执行格式检查。IntelliJ IDEA 可以导入
[`config/formatter/eclipse-java-redis-ops.xml`](config/formatter/eclipse-java-redis-ops.xml)，使 IDE
格式化结果与 Maven 保持一致。
