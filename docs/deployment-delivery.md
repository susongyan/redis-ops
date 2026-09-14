# 测试 / 生产部署交付入口

状态：独立代码目录、构建入口和本地核心链路已实现；本文提供部署步骤与模板。
公司 Git 仓库、Maven 私服、CI、Apollo 服务及 Namespace 的建设不属于本次交付。
本地实测不等于生产验收；上线前必须在自己的网络、权限、数据规模下完成下述检查。

## 1. 发布边界与文件清单

以审核过的 commit/tag 为发布输入，不直接打包有未提交文件的工作区。
本次完整初始化快照为 V30，位于 `redis-ops-platform/sql/latest/`；历史增量保留在 Platform migration 目录。
将来以所选发布 JAR 内实际包含的 migration 为准，不在运维脚本中写死最高版本。

| 交付件 | 源码位置 | 安装位置示例 |
| --- | --- | --- |
| Platform JAR | redis-ops-platform/bootstrap/target/redis-ops-platform-bootstrap-0.1.0-SNAPSHOT.jar | /opt/redis-ops-platform/app/platform.jar |
| Worker JAR | redis-ops-sync-worker/sync-service/target/redis-ops-worker-service-0.1.0-SNAPSHOT.jar | /opt/redis-ops-worker/app/sync-worker.jar |
| 前端静态文件 | redis-ops-frontend/dist/ | /opt/redis-ops-frontend/dist/ |
| Platform 外部配置 | [application-pro.yml.example](../redis-ops-platform/deploy/config/application-pro.yml.example) | /etc/redis-ops-platform/application-pro.yml |
| Worker 外部配置 | [application-pro.yml.example](../redis-ops-sync-worker/deploy/config/application-pro.yml.example) | /etc/redis-ops-worker/application-pro.yml |
| 数据库准备 | [数据库手册](../redis-ops-platform/sql/README.md)及同目录 SQL 模板 | DBA 审批后执行 |
| Nginx 配置 | [前端部署说明](frontend-deployment.md) | Nginx 站点配置目录 |

contract 是构建依赖，不是部署服务。消费者解析固定版本 artifact；私服配置由公司负责。
当前根 scripts 的合并发布包仅为兼容/本地联调入口，不是三个独立项目的必要依赖。

## 2. 机器、数据库和网络

- 最小功能验证：一台机器运行 Nginx、Platform、Worker，外加一套 MySQL。源/目标 Redis 是被管理的数据系统。
- 正式环境可将 Platform/Nginx 与 Worker 分机；按可用性目标配置 MySQL 高可用，并为 Worker 预留独占持久盘。
- 只需一个共享 MySQL 逻辑库 `redis_governance`，不是 Platform/Worker 各建一份。区分迁移、Platform 运行、Worker 运行三个账号。
- 不固定承诺 CPU/内存或吞吐容量：先测源数据量、写入速率、带宽与可接受追平时间。Worker 默认并发 2，spool 容量不能用 JVM 堆大小替代。

| 发起方 | 目标 | 用途 |
| --- | --- | --- |
| 用户浏览器 | Nginx HTTPS | 页面和同域 API |
| Nginx | Platform 8080（可调整） | /api/ 代理 |
| Platform | MySQL 3306；受管 Redis | 元数据、迁移、采集、校验、治理 |
| Worker | 同一个 MySQL；源/目标 Redis | 领取 Job、租约、实际同步 |
| 监控/管理网 | 两个进程 Actuator | 健康与指标 |

Worker 不依赖 Platform HTTP。Cluster 场景必须能到达拓扑返回的全部节点地址，不能仅打通 seed。
真实 Redis ACL 应按启用功能审核；同步预检查涉及复制能力，目标重置只可走明确确认流程。
不要把本地无密码 Redis、三主无副本 Cluster 或 Compose 当成生产配置。

## 3. 独立构建

在各自代码根目录执行（Maven 已配置可解析 contract）：

```bash
# redis-ops-platform/
mvn clean verify
# redis-ops-sync-worker/
mvn clean verify
# redis-ops-frontend/
npm ci
npm run build
```

后端使用 Java 17+、Maven 3.9+；前端构建使用 Node 20+。运行前端只需 Nginx，不需要 Node。
记录 commit、JAR/静态包校验和、migration 清单及测试证据。不得把真实配置和密钥装进发布包。

## 4. 首次部署顺序

1. DBA 按[数据库手册](../redis-ops-platform/sql/README.md)准备数据库与账号；可以选择
   [最新完整 SQL 初始化包](../redis-ops-platform/sql/latest/README.md)，或空库由 Flyway 逐条迁移，二选一。
   完整 SQL 已包含建库和 BASELINE，不再重复建库或执行 V1–V30；先不启动 Worker。
2. 将两个示例配置分别复制到各自 `/etc/redis-ops-*/application-pro.yml`，替换所有占位符。
3. 两边配置同一数据库和同一 Redis 凭据密钥环；不要每次启动生成新密钥。
4. 以独立部署用户创建应用日志目录与 Worker 数据目录；配置只对服务用户开放读取，目录按公司策略保护。
5. 启动 Platform，观察 Flyway 校验/迁移成功、健康 UP，确认 schema history 与发布内容一致。
6. schema 就绪后启动 Worker，确认健康 UP 且无 SQL、密钥解析或周期调度异常。
7. 配置 Nginx 指向 Platform；验证页面加载和 `/api/v1/clusters`，再开始功能验收。

首次验证可以在终端前台运行以下命令；持续运行交给公司进程管理器/systemd，不能依赖终端保持在线：

```bash
java -Xms512m -Xmx1024m -jar /opt/redis-ops-platform/app/platform.jar \
  --spring.profiles.active=pro \
  --spring.config.additional-location=file:/etc/redis-ops-platform/

java -Xms1g -Xmx2g -jar /opt/redis-ops-worker/app/sync-worker.jar \
  --spring.profiles.active=pro \
  --spring.config.additional-location=file:/etc/redis-ops-worker/
```

以上堆大小仅为起步示例，应按压力测试调整。外部目录使用绝对路径且末尾 `/`，故意不加 `optional:`，
避免配置目录缺失时静默回退；仍需部署检查文件存在、占位符已替换。启动日志不得输出配置内容。
UAT 可复制为 `application-uat.yml` 并改启动 profile 为 `uat`；每套环境使用不同数据库及密钥。

systemd 可直接使用上述命令作为 ExecStart，设置 User/Group、WorkingDirectory、Restart=on-failure，
并确保停止超时足够优雅关闭。已有 redis-opsctl/systemd 模板见角色部署文档，但属于兼容入口：
脚本会通过命令行传入 profile、Platform 端口；只使用外部 YAML 时优先采用本节直接启动方式。

## 5. 配置原则与安全边界

分析 Agent 按请求调用，无启用开关；外部地址配置、监控采集开关与间隔见 [配置说明](runtime-switches.md)。

- Spring 属性是统一入口：数据库密码直接写 `spring.datasource.password`，密钥环写 `redis-ops.credential.keys`。
  可以放外部配置或由配置中心注入，不要求必须使用环境变量。
- 现有 `${DB_URL:...}` 等大写名称只是兼容入口，不是 Apollo 必须使用的属性名。
- 常规优先级（高到低）：命令行参数、Java system properties、环境变量、Config Data；外部附加位置可覆盖内置配置，
  同一位置 profile 文件覆盖通用文件。import 引入值可能覆盖声明它的文档，避免重复配置同一属性。
- 仓库内置 pro 的数据库地址/账号是示例，不能直接作为生产凭据。检查启动脚本是否传入额外参数导致覆盖。
- 密钥格式 `keyId:Base64(AES-256),oldKeyId:Base64(AES-256)`；每个解码后恰好 32 字节。
  当前首个 key 用于新加密，旧 key 必须保留直到旧密文完成迁移。AES-GCM 加解密和 MySQL 存密文逻辑不变。
- 配置中心承担密钥存储权限、访问审计和发布控制；应用仍负责正确使用密钥，不把秘密放进 API、日志、审计或任务 payload。
- 不承诺热更新：数据源、密钥对象、并发/租约等先按受控重启生效管理；Apollo 接入见[专门说明](apollo-integration.md)。

## 6. 验收与升级

```bash
curl --fail http://127.0.0.1:8080/actuator/health
curl --fail http://127.0.0.1:8081/actuator/health
```

健康 UP 只是基础条件：检查周期日志无 SQL 错误；通过页面发现源/目标资产；在隔离测试数据上跑预检查、
确认启动、全量/增量、暂停隔离、恢复追平、安全结束、FULL 校验和风险扫描；检查审计。
Cluster 检查全部主节点通道；生产前另测优雅停机、租约接管、generation/fence、断网与磁盘不足失败关闭。
[本地实测记录](local-smoke-2026-09-12.md)仅供参考，不替代部署环境验收。

上线前必须配置网络访问限制和 HTTPS。V31 起已启用本地用户、角色和共享 MySQL 会话，
首次部署必须配置临时管理员密码并强制改密，详见[用户登录部署](user-access.md)。
`X-Operator` 不再作为身份来源；脚本须携带真实登录会话及 CSRF。企业 SSO/LDAP 仅预留扩展。

升级前备份数据库并演练恢复，检查 schema 与旧版兼容性，暂停/结束活跃同步任务。
先完成 Platform migration，再启动匹配版本 Worker；滚动/混版本运行需单独验证，不默认保证。
Worker spool 不随发布删除，不能让两个实例共享目录。回滚应用不代表回滚 schema，不随意执行 DROP 或 Flyway repair。

## 7. 常见故障

| 现象 | 检查项 |
| --- | --- |
| Flyway checksum / 非空库无历史 | 是否混入未提交 migration，是否手工执行过 DDL；停止部署交 DBA 核对 |
| 启动连 mysql-pro 或默认账号 | 外部文件/profile 路径及更高优先级覆盖 |
| 凭据无法解密 | 两侧 keyId/密钥环是否一致、是否遗漏旧 key；不要输出密钥排查 |
| 页面正常而 API 502 | Nginx 后端地址和网络；后端是否 UP |
| Worker UP 但任务失败 | 调度日志、表权限、实例租约、spool 空间和 Cluster 全节点网络 |
| Apollo 发布无效 | 是否已引入适配器、身份/Namespace/环境正确、是否需要重启 |
