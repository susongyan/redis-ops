# 独立仓库迁移与验证

当前工作区已按四个顶层代码目录物理拆分，根目录保留本地联调能力，`scripts/export-split-repositories.sh` 用于生成四个不共享源码的
独立仓库：

```text
redis-ops-sync-contract
redis-ops-platform
redis-ops-sync-worker
redis-ops-frontend
```

## 导出

目标目录必须为空，脚本不会覆盖已有仓库：

```bash
./scripts/export-split-repositories.sh /tmp/redis-ops-repositories
```

导出时会为 Platform 和 Worker 生成不同的 Maven Parent。两者不包含 contract 源码，而是固定依赖
`io.github.susongyan.redisops:redis-ops-sync-contract:0.1.0`。Frontend 仅包含 Node/Vite 文件。

## 隔离验证

```bash
./scripts/validate-split-repositories.sh /tmp/redis-ops-repositories
```

验证脚本使用四个临时目录模拟独立 Maven 环境：先把 contract 发布到临时 file Registry，再使用
不同的空本地 Maven 仓库分别构建 Platform 和 Worker，最后执行前端的 `npm ci` 与生产构建。
同时检查两侧源码目录和 Maven 依赖树，防止重新引入对方的实现模块。

## 企业 Registry

contract 仓库包含手动发布流水线模板。公司创建 Maven Registry 后，在四个仓库中配置：

- `MAVEN_REGISTRY_URL`
- `MAVEN_REGISTRY_USERNAME`
- `MAVEN_REGISTRY_PASSWORD`

Registry 的权限、审计、凭据轮换由外部平台负责。Platform 和 Worker CI 只解析已发布的固定 contract
版本；升级契约时先发布新版本，再分别升级两个消费者。

当前导出是工作区快照，会包含未提交的源文件，必须先核对快照再导入企业仓库。
`0.1.0` 是首次发布候选版本；如果企业 Registry 已存在该版本，应升级版本，禁止覆盖。
前端无需 Maven Registry Secret。消费者本地构建可配置自己的 Maven settings，或设置上述环境变量后
执行 `mvn -s .mvn/settings.xml clean verify`。

## 部署文件布局

每个运行仓库导出 `deploy/bin/redis-opsctl`、`deploy/conf` 和对应 systemd 模板。
将这三个目录分别安装到安装根目录的 `bin/`、`conf/`、`systemd/`；Platform JAR 放到
`app/platform.jar`，Worker JAR 放到 `app/sync-worker.jar`，前端 `dist/` 内容放到 `frontend/`。
只启动对应角色：`bin/redis-opsctl run platform`、`run worker` 或 `run frontend`。
配置与 systemd 占位符需要按实际机器填写；这些模板尚未代表生产部署已验收。

## 建仓顺序

1. 导出并完成隔离验证。
2. 先创建并发布 `redis-ops-sync-contract`。
3. 创建 Platform 和 Worker 仓库，配置 Maven Registry Secret 后执行各自 CI。
4. 创建 Frontend 仓库并执行 Node CI。
5. 分别配置部署流水线；Monorepo 仅继续承担本地 Compose 联调。
