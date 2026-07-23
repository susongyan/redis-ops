# Redis Governance Platform

Redis 轻量级运维治理平台。当前优先完成 Phase 1 的 Redis 资源管理：集群及内置连接认证、应用与绑定、节点快照和异步拓扑发现。同步任务将在资源管理完成后继续设计；RBAC 暂不实现。

## 工程结构

```text
redis-governance-platform
├── governance-common          # 通用类型、错误码和基础工具
├── governance-domain          # 领域模型、状态机和仓储接口
├── governance-application     # 用例编排、命令/查询服务
├── governance-infrastructure  # MyBatis、Redis/同步工具适配器
├── governance-api             # REST API、DTO、统一异常处理
├── governance-bootstrap       # API 与异步任务单进程启动入口
├── docs                       # 架构、ER 与任务拆分
└── sql                        # 数据库 migration
```

## 本地构建

要求 JDK 17、Maven 3.9+、Node.js 20+ 和 Docker。

```bash
mvn verify
cd frontend && npm install && npm run build
```

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

验收脚本会启动无认证 Standalone、ACL Standalone、Sentinel 和三主节点 Cluster，验证连通性、
异步拓扑发现、失败快照保留、幂等、审计及秘密脱敏。只启动这些 Redis 测试实例可执行：

```bash
./scripts/redis-asset-test-up.sh
```

详细设计见 [架构设计](docs/architecture.md)、[跨机房关系与同步](docs/cross-idc-sync.md)、[RPO 计算与切换判定](docs/rpo-calculation-and-switchover.md) 和 [Phase 1 任务拆分](docs/phase1-tasks.md)。

Redis 资产模块的启动方式和接口见 [Asset Management API](docs/asset-management-api.md)。
