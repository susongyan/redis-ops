# 数据库初始化、DDL 与升级

## DBA 直接初始化：完整 SQL

新环境使用 [最新完整初始化包](latest/README.md) 中的
`latest/redis-governance-init.sql`，包含建库、最终完整 DDL、必需种子数据以及
由 Flyway 实际生成的 BASELINE，当前为 V29。只看完整表定义时使用 `latest/schema.sql`。
完整包导入与下文“空库运行全部 migration”是两个替代方案，不能重复执行。

## 唯一 schema 来源

业务 DDL/DML 在 `../bootstrap/src/main/resources/db/migration/`，随 Platform JAR 发布。
本目录 `.sql.template` 仅用于 DBA 建库/授权，不会自动执行；完整快照由隔离迁移生成，不手工维护第二套 schema。
已发布 migration 禁止修改/重编号。完整快照维护在 `latest/`，历史迭代变化保留在上述 migration 目录，不复制第二套增量 SQL。
当前最新快照为 V29，初始化后跳过 V1–V29；`baseline-v26/` 只用于历史归档和升级回归。
以所选 commit/tag 的 JAR 和 `latest/manifest.json` 为准：

```bash
jar tf /opt/redis-ops-platform/app/platform.jar | sort | sed -n '/BOOT-INF\/classes\/db\/migration\//p'
```

数字版本由 Flyway 排序，不能按文件名字典序执行（V10 不能排在 V2 前）。DBA 审核所选 release
完整 migration 目录和校验和，不从脏工作区复制 SQL。

## 首次初始化（空库）

1. DBA 填写并审核 `00-create-database.sql.template` 的来源主机和密码，创建一个 MySQL 8.x
   逻辑库 `redis_governance` 供两进程共享。账号区分迁移、Platform、Worker。
2. Platform 外部 YAML 的 datasource 使用运行账号，`spring.flyway.user/password` 使用迁移账号，
   `spring.flyway.enabled=true`。先只启动一个 Platform，不启动 Worker。
3. Flyway 在启动时创建 schema history，按数字版本运行尚未应用的 migration。
   它不创建 MySQL 实例、数据库或账号，这些由步骤 1 完成。
4. 迁移成功后，DBA 执行 `10-worker-grants.sql.template` 的逐表授权，再启动 Worker。
5. 确认 history 成功且版本与发布清单一致，不能只看进程存活：

```sql
SELECT installed_rank, version, description, success
FROM redis_governance.flyway_schema_history ORDER BY installed_rank;
```

模板不能原样执行。真实密码只填入受控 SQL 会话/配置，不进入终端参数、日志或 Git。
生产也可在专门发布阶段用相同 migration 和兼容 Flyway/MySQL 组件迁移，再让业务进程禁用 Flyway；
仅关闭 Flyway 不会创建表。JDBC 必须使用 `serverTimezone=UTC`。

## DBA 手工执行 DDL

首次初始化优先使用上面的已验证完整包，其基线由实际 Flyway 生成，不伪造 SQL checksum。
对于其他已有库或非标准手工建表情形，推荐 DBA 审核 SQL，仍由 Flyway 执行以维护版本/checksum。若公司强制手工执行，必须另审批
执行清单与 Flyway 历史对齐方案；不提供伪造 history 的 INSERT。
非空库无 Flyway 历史时不能直接认定已迁移。不要为了消除报错随意打开 baseline-on-migrate、
执行 baseline/repair。baseline 仅声明旧版本已应用，不验证 schema 等价。
先在副本核对表、索引和初始化数据，再由 DBA 确定精确 baseline 版本及迁移策略。

## 升级和回滚

- 备份数据库并演练恢复；审核新 migration 和 Worker 逐表授权差异。
- 暂停/结束活跃同步任务，先迁移 Platform schema，再启动匹配版本 Worker。
- Worker 无 DDL 权限、资产表只读；表级权限不能限制 async_job 的某类行，不是行级隔离。
- MySQL DDL 不保证整批事务回滚；失败后核对已执行语句，不盲目重复启动。
- 回滚 JAR 不等于回滚 schema；旧版兼容性必须验证，禁止删库重建或修改已执行 SQL 处理升级失败。

返回 [部署交付入口](../../docs/deployment-delivery.md)。
