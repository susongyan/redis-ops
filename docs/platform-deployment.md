# Platform 前后端构建与部署

本文说明如何从源码构建 Redis Governance Platform，并在 Linux 上以原生进程部署
Platform API 与 Nginx 前端。生产 MySQL 和被管理 Redis 均为外部依赖。

## 1. 环境要求

- Linux、Bash、基础命令行工具和 `curl`
- JDK 17 或更高版本
- Maven 3.9 或更高版本
- Node.js 20 或更高版本、npm
- Nginx
- 可访问的 MySQL 8.x

构建机需要 JDK、Maven、Node.js 和 npm。部署机仅需 Java、Nginx、Bash 和 curl。

## 2. 从源码生成发布包

在仓库根目录执行：

```bash
./scripts/build-release.sh
```

脚本默认依次执行 Maven 全仓验证、`npm ci` 和前端生产构建，然后在 `release/` 下生成
`redis-ops-<version>.tar.gz`。只在已由 CI 完成测试时才建议使用：

```bash
./scripts/build-release.sh --skip-tests
```

也可以使用 `--output-dir /path/to/output` 指定产物目录。发布包固定包含两个可执行 JAR、
前端静态文件、Nginx 模板、systemd 模板及控制脚本。

## 3. 解压和配置

默认使用 JAR 内置的 `dev` Profile，开发环境无需复制配置文件即可启动：

```bash
bin/redis-opsctl start all
```

如需切换环境，可在启动前设置 `REDIS_OPS_PROFILE=fat|uat|pro`，或在可选的
`conf/redis-ops.env` 中配置。该文件不存在时控制脚本不会报错，而是直接使用内置 Profile。
后续接入 Apollo 时可继续沿用这些 Spring 属性名。

以普通部署用户操作：

```bash
mkdir -p /opt/redis-ops
tar -xzf redis-ops-0.1.0.tar.gz -C /opt/redis-ops --strip-components=1
cd /opt/redis-ops
cp conf/redis-ops.env.example conf/redis-ops.env
chmod 600 conf/redis-ops.env
```

使用外部覆盖文件时，至少修改以下配置：

```bash
DB_URL='jdbc:mysql://mysql.example.internal:3306/redis_governance?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC'
DB_USERNAME='redis_ops_platform'
DB_PASSWORD='...'
REDIS_OPS_CREDENTIAL_KEYS='v1:...'
PLATFORM_PORT=8080
FRONTEND_BIND='0.0.0.0'
FRONTEND_PORT=8088
PLATFORM_PROXY_URL='http://127.0.0.1:8080'
```

`REDIS_OPS_CREDENTIAL_KEYS` 必须稳定保存。Platform 与所有 Sync Worker 必须使用相同密钥环；
更换或丢失密钥会导致集群密码无法解密。配置文件必须为当前用户可读，且 group/other 不得有
任何权限，控制脚本会拒绝加载权限高于 `600` 的文件。

建议 Platform 数据库账号拥有平台业务表和 Flyway schema history 所需的 DDL/DML 权限。
首次启动由 Platform 执行 V1–V11 migration，后续启动由 Flyway 自动识别已执行版本，不会
重复迁移。

## 4. 检查、启动和停止

先执行无副作用检查：

```bash
bin/redis-opsctl doctor platform
bin/redis-opsctl doctor frontend
```

启动 Platform 与前端：

```bash
bin/redis-opsctl start platform
bin/redis-opsctl start frontend
```

也可以在同机一次启动全部角色：

```bash
bin/redis-opsctl start all
```

`start all` 按 Platform、Frontend、Sync Worker 的顺序启动，并等待每个角色健康后继续。
查看状态、健康和日志：

```bash
bin/redis-opsctl status
bin/redis-opsctl health platform
bin/redis-opsctl logs platform
bin/redis-opsctl logs frontend
```

停止顺序与启动相反：

```bash
bin/redis-opsctl stop frontend
bin/redis-opsctl stop platform
```

控制脚本使用发布目录内的 PID、日志及 Nginx 临时目录。停止时先发送 SIGTERM，并在
`STOP_TIMEOUT_SECONDS` 后才强制终止。陈旧 PID 或 PID 已属于其他进程时不会误杀进程。

## 5. 前后端分机

前端机器只需保留发布包中的 `frontend/`、`bin/`、`conf/` 和 `var/` 目录，并设置：

```bash
PLATFORM_PROXY_URL='http://platform.internal.example:8080'
```

然后执行 `bin/redis-opsctl start frontend`。Nginx 对 `/api/` 反向代理，其他路径使用
`index.html` 回退，因此 React 路由刷新不会返回 404。生产环境可在本 Nginx 前再接入负载
均衡和 HTTPS；当前模板本身不终止 TLS。

## 6. 安装 systemd

先在配置中设置真实部署用户和组：

```bash
DEPLOY_USER='redisops'
DEPLOY_GROUP='redisops'
```

安装并启用 unit：

```bash
bin/redis-opsctl install-systemd platform
bin/redis-opsctl install-systemd frontend
sudo systemctl start redis-ops-platform redis-ops-frontend
```

也可使用 `install-systemd all`。安装操作会把带绝对发布路径的 unit 写入
`/etc/systemd/system`，因此安装后若移动发布目录，必须重新安装 unit。systemd 模式下使用：

```bash
sudo systemctl status redis-ops-platform
sudo journalctl -u redis-ops-platform -f
```

不要同时使用 systemd 和 `redis-opsctl start` 启动同一角色。

## 7. 故障检查

- `doctor` 报缺失配置：检查 `conf/redis-ops.env`，脚本不会打印秘密值。
- 配置权限错误：执行 `chmod 600 conf/redis-ops.env`。
- 端口占用：调整对应端口或停止占用进程。
- Platform 健康超时：检查 `var/log/platform.log`、MySQL 网络与账号权限。
- 前端启动失败：执行 `nginx -t -p "$PWD/" -c "$PWD/var/nginx/nginx.conf"`。
- `/api` 返回 502：检查 `PLATFORM_PROXY_URL` 以及前端机器到 Platform 的网络。

开发环境仍使用仓库的 `compose.yaml` 启动 MySQL 和测试 Redis，不应把该 Compose 文件作为
生产部署方案。
