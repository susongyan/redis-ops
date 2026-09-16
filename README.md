# Redis Ops · Redis 运维治理平台

面向授权运维人员的 Redis 旁路管理平台，提供资产、同步、观测分析和治理能力，不承载业务 Redis 流量。

**[功能介绍与截图](docs/demo/README.md)** · **[测试 / 生产部署](docs/deployment-delivery.md)** · **[用户登录与权限](docs/user-access.md)**

## 当前能力与边界

| 领域 | 已实现 | 边界 |
| --- | --- | --- |
| 资产 | Standalone / Sentinel / Cluster 登记、Region / IDC、连接测试、拓扑发现 | 不负责 Redis 安装、扩缩容或自动切换 |
| 应用 | 应用与集群多对多、双向维护，新增集群可多选应用 | 人工资产关系，不修改业务连接配置 |
| 同步 | 独立 Worker 全量 / 增量同步、租约、checkpoint、fence 与任务控制 | 写入必须遵守预检查和确认；上线前按版本及规模验收 |
| 数据校验 | 全量及抽样，缺失、额外、类型、TTL、摘要差异 | 大 Key 可安全降级，严格模式不把降级结果视为自动放行依据 |
| 观测与治理 | 指标采集、风险扫描、告警、TTL 治理、数据清理、受控 Redis Console | 依赖环境权限和配置，不表示当前部署全部可用 |
| Key 分布 | 按需预览、固定容量 / 有界 Top-K、规则快照、CSV 导出 | SCAN 观测次数，不精确去重、不读取 value、不占用 Sync Worker |
| 用户 | 本地账号、ADMIN / OPERATOR、MySQL 共享会话 | 无集群级权限；企业 OIDC / LDAP 仅预留扩展，未提供登录入口 |
| AI 分析 | 按需分析入口与外部适配边界 | 依赖外部服务配置，不赋予 Agent Redis 修改权限 |

监控页面通过[受登录保护的业务指标接口](docs/collector-metrics-api.md)读取快照。管理端点默认访问边界见[用户说明](docs/user-access.md)，不能假设 Prometheus 匿名可用。

## 工程结构

```text
redis-ops/
├── redis-ops-platform/       # REST API、控制面与采集治理，独立 Maven 根
├── redis-ops-sync-worker/    # 同步数据面，独立 Maven 根
├── redis-ops-frontend/       # React 前端，独立 npm 根，产物为静态文件
├── redis-ops-sync-contract/ # 纯 Java 契约 artifact，不是运行服务
├── docs/                    # 架构、部署和使用说明
└── compose.yaml             # 本地联调 MySQL / Redis
```

Platform 与 Worker 共享一个 MySQL 逻辑库，通过控制表和契约协作；Worker 不依赖 Platform HTTP 或实现模块。Platform 负责 Flyway，Worker 不执行迁移。

Platform 的通用 Job 执行器与独立 Sync Worker 不同：`platform.jobs.enabled`（环境变量 `PLATFORM_JOBS_ENABLED`）控制前者，不是同步进程开关，也不关闭按需 Key 分布执行器。该开关同时控制后台凭据重加密。

三个运行项目可独立构建部署；公司拆仓、私服、CI 和 Apollo 服务建设由企业负责。详见[拆仓说明](docs/split-repository-migration.md)。

Java 包和 Maven groupId 统一为 `io.github.susongyan.redisops`，模块子包为
`platform`、`worker` 和 `sync.contract`。旧命名空间不提供兼容，需先安装新坐标的契约再构建消费者，
详见 [ADR-020](docs/adr/ADR-020-author-package-prefix.md)。

## 构建

- 后端编译目标 Java 17，可使用 JDK 17 或 21；Maven 3.9+。
- 当前 Vite 要求 Node.js `^20.19.0 || >=22.12.0`；部署静态文件不需要 Node。
- Docker / Colima 用于本地依赖及隔离测试，不是运行 JAR 的必需条件。

仓库根执行：

```bash
mvn -f redis-ops-sync-contract/pom.xml clean install
./scripts/build-platform.sh
./scripts/build-sync-worker.sh
cd redis-ops-frontend
npm ci
npm run build
```

能够解析 contract artifact 后，可在 Platform / Worker 各自根目录执行 `mvn clean verify`。仓库根没有 Maven Parent。

## 本地快速开始

仅用于隔离本机环境，不要将 Compose 默认账号和无认证 Redis 暴露到公网。已有实例先检查端口，避免重复启动。

### 1. 启动依赖并构建

```bash
docker compose up -d mysql redis redis-sync-target
```

等待 MySQL 健康，按上节构建。新空库由 Platform Flyway 初始化；已有库按增量升级，不重复导入完整 SQL。

### 2. 准备外部配置

在仓库外创建并保护本地配置，例如 `/absolute/path/local-redis-ops.yml`。以下模板必须替换占位符：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/redis_governance?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
    username: redis_governance
    password: redis_governance
identity:
  bootstrap:
    username: admin
    password: "REPLACE_WITH_INITIAL_PASSWORD"
  cookie-secure: false # 仅本地 HTTP；生产 HTTPS 保持 true
redis-ops:
  credential:
    keys: "v1:REPLACE_WITH_BASE64_32_BYTE_KEY"
```

凭据密钥可首次用 `openssl rand -base64 32` 生成并填入配置。**重启必须复用原密钥**，不可每次重新生成。配置不得提交 Git；Platform / Worker 使用相同密钥环解密 Redis 凭据。

管理员初始密码没有内置默认值。首次登录必须改密，完成后移除配置中的初始密码。重启不覆盖现有账号，详见[用户接入](docs/user-access.md)。

### 3. 启动 Platform 和前端

仓库根终端：

```bash
java -jar redis-ops-platform/bootstrap/target/redis-ops-platform-bootstrap-0.1.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:/absolute/path/local-redis-ops.yml
```

另一个终端：

```bash
cd redis-ops-frontend
npm run dev
```

访问 `http://127.0.0.1:5173`，用自己配置的账号登录并改密。API 默认 8080。开发代理及生产同源 Nginx 配置见[前端部署](docs/frontend-deployment.md)。

此步骤不启动同步数据面。执行同步还需按[Worker 部署](docs/sync-worker-deployment.md)配置共享数据库、密钥、唯一身份与 spool 持久目录并启动 Worker。不要为了查看页面启动真实同步。

## 测试 / 生产部署

以[部署交付入口](docs/deployment-delivery.md)为操作导航：

1. 选择审核过的 commit/tag，独立构建三个运行项目。
2. DBA 初始化空库使用 [sql/latest 完整包](redis-ops-platform/sql/latest/README.md)，版本与校验和以 manifest 为准；已有库只做增量升级。
3. 配置两进程各自的 Spring 外部 YAML、数据库账号、密钥与初始管理员。配置存储安全交给外部部署系统 / 配置中心，应用 AES-GCM 逻辑不变。
4. Nginx 托管静态文件，HTTPS 同源代理 `/api/`。会话存储在共享 MySQL，不需要 Redis 或粘性会话。
5. 验证登录、权限、迁移和所需核心流程，再按生产数据规模验收。

Apollo 需企业提供服务并接入适配器，不是只填写 Namespace 就生效，见[Apollo 边界](docs/apollo-integration.md)。根目录合并发布包及控制脚本仅保留为兼容入口，不替代独立部署手册。

## 验证与规范

日志默认写入启动工作目录的 `log/platform.log`、`log/worker.log`，格式与滚动策略见[日志配置](docs/logging.md)。

后端在对应 Maven 根执行 `mvn verify`，格式化执行 `mvn spotless:apply`。

```bash
cd redis-ops-frontend
node --test src/*.test.js
npm run build
```

隔离测试入口包括 `scripts/asset-smoke.sh`、`scripts/verify-identity.mjs`、`scripts/sync-version-matrix.sh` 和 `scripts/sync-cluster-it.sh`。先阅读脚本及手册，核对目标及影响范围；资产验收需要登录凭据配置，见[用户说明](docs/user-access.md)。不得指向生产环境。

## 文档导航与维护

| 主题 | 入口 |
| --- | --- |
| 功能截图与演示 | [图册](docs/demo/README.md) |
| 机器、数据库、配置、上线 | [部署交付](docs/deployment-delivery.md) |
| 登录、角色、共享会话 | [用户接入](docs/user-access.md) |
| 应用与集群关系 | [关联管理](docs/application-bindings.md) |
| Key 分布及导出 | [分布分析](docs/key-distribution.md) |
| 同步恢复与安全 | [生命周期](docs/sync-worker-lifecycle.md)、[运行手册](docs/sync-operations-runbook.md) |
| 架构与约束 | [架构契约](docs/architecture-contract.md)、[ADR](docs/adr/README.md) |

README 维护当前能力和入口，不重复固化最高数据库版本或完整配置清单。功能、认证、依赖和部署方式变化时同步检查本文。历史 Phase 计划仅用于追溯，不作为当前交付状态或操作指南。
