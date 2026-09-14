# 最新完整数据库初始化包

当前版本 **V30**，验证环境 MySQL 8.4.11：46 张业务表、1 张 Flyway 历史表、15 条命令种子数据。
包含 Key 分布任务、运行槽和分组表，以及已有 Worker IP 字段。当前版本与 SHA256 以 manifest.json 为准。

## DBA 直接使用

执行 [redis-governance-init.sql](redis-governance-init.sql)，包含建库、完整表结构、必需种子数据和真实 V30 BASELINE。
仅用于目标数据库尚不存在的全新环境。已有数据库升级不得导入本文件，也不要使用 mysql --force。

```bash
mysql --host=MYSQL_HOST --port=3306 --user=DBA_USER --password \
  --default-character-set=utf8mb4 < redis-governance-init.sql
```

执行前核对目标实例和发布包，停止应用启动；默认库名为 redis_governance。
若 DBA 已预建完全空库，审核移除 CREATE DATABASE 语句后执行；自定义库名须同步审核 SQL、授权和 JDBC 配置。
账号授权使用上级目录模板，但不要重复执行 CREATE DATABASE。

```sql
SELECT version, type, success FROM redis_governance.flyway_schema_history;
SELECT COUNT(*) FROM redis_governance.operation_command_definition;
```

预期 history 为一条 version=30、type=BASELINE、success=1，命令数为15。
Platform 启动后 validate 通过，V1–V30 不再执行；未来 V31 起由 Flyway 增量升级。Worker 不执行 Flyway。
其他 MySQL 8.x 小版本应先在 UAT 验证。

## 文件分工

| 文件 | 用途 |
|---|---|
| redis-governance-init.sql | DBA 一次性初始化入口 |
| schema.sql | 最新完整业务表 DDL，仅供结构审核或分步初始化 |
| seed.sql | 迁移定义的必需种子数据，无现有环境业务数据或凭据 |
| flyway-baseline.sql | 由真实 Flyway 生成的基线表与记录 |
| manifest.json | 版本、来源、迁移和输出文件 SHA256、验证结果 |

整合文件和分文件二选一，不能重复执行。分文件须在空库按 schema、seed、baseline 顺序执行。
BASELINE 时间是生成时间，不能代替 DBA 在目标环境的执行审计。

## 与历史增量分开维护

本目录始终保存一份最新快照，文件名不随版本改变。
历史增量的唯一来源是 `../../bootstrap/src/main/resources/db/migration/V*__*.sql`，随 Platform JAR 发布。
已发布增量禁止修改、删除、重编号。`../baseline-v26/` 仅作历史归档和升级回归输入，不是新环境默认入口。

新增 migration 后，在仓库根执行 `node scripts/generate-database-baseline.mjs` 更新本目录。
脚本自动识别最高连续版本，在独立临时 MySQL 生成并回灌，对比全部表结构、列元数据及种子数据，
验证 Flyway validate 与 migrate=0；不连接现有业务库。生成成功后审核并将迁移、快照和 manifest 一起提交。
脚本拒绝修改过的已提交 migration；允许新的未提交 migration，并在 manifest 明确标记，不能仅凭 sourceCommit 还原这种工作区生成结果。
发布前须逐项核对 manifest 中的迁移哈希与最终 release 源码一致，不能把未提交工作区当作已发布版本。
