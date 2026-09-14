# DBA 完整初始化包：V26

历史归档与升级回归输入。新环境请使用 [最新完整初始化包](../latest/README.md)，本目录不再覆盖更新。

本次已验证环境：MySQL 8.4.11；41 张业务表 + 1 张 Flyway 历史表，15 条命令定义初始化数据。
其他 MySQL 8.x 小版本须在对应 UAT 环境先验证，不能仅凭语法兼容认定生产可用。

## 直接使用哪个文件

执行 **redis-governance-v26-init.sql**。该文件包含：

1. 创建 `redis_governance` 数据库并选中它。
2. V1–V26 执行后的最终完整业务表 DDL（不是增量 ALTER 拼接）。
3. 迁移定义的必需初始化数据，不包含现有环境的业务数据或凭据。
4. 由 Flyway 实际生成的 V26 `BASELINE` 历史表与记录，使应用跳过 V1–V26。

仅适用于 **全新数据库环境，目标库尚不存在**。不使用 IF NOT EXISTS 掩盖旧结构，不包含 DROP DATABASE/TABLE。
若数据库由 DBA 预先创建，须确认完全空库，并审核移除开头 CREATE DATABASE 语句后再执行。
已有数据或已有 Flyway history 的库必须使用增量升级，不能导入本文件。

## DBA 执行步骤

1. 核对 `manifest.json` 的源 commit、migration SHA256 与发布版本，审核 SQL 和初始命令策略。
2. 备份/确认目标环境和连接，确保 Platform、Worker 尚未启动。
3. 执行完整 SQL。密码交互输入，**不要使用 `--force`**，出现错误立即停止检查：

```bash
mysql --host=MYSQL_HOST --port=3306 --user=DBA_USER --password \
  --default-character-set=utf8mb4 < redis-governance-v26-init.sql
```

默认库名固定为 `redis_governance`；自定义库名必须审核修改开头 CREATE DATABASE/USE、账号授权及应用 JDBC。
4. 按上级目录账号模板创建运行/迁移账号并授权。**不要再执行该模板中的 CREATE DATABASE**，完整 SQL 已建库。
   然后执行 `10-worker-grants.sql.template` 的逐表授权；模板占位符必须替换并经审核。
5. 检查业务表数量和 seed 行数与 manifest 一致，检查 baseline：

```sql
SELECT version, type, success FROM redis_governance.flyway_schema_history;
SELECT COUNT(*) AS command_count FROM redis_governance.operation_command_definition;
```

history 应是一条 version=26、type=BASELINE、success=1，不是伪造的 26 条 SQL 执行记录。
不要重复运行 Flyway baseline，不启用 baseline-on-migrate，不对本包执行 repair。
6. 配置 Platform 指向该库、启用 Flyway，启动时 validate 应成功，V1–V26 执行数量为 0。
   未来发布新增 V27+ 时，仍由 Flyway 正常执行。Worker 的 Flyway 始终关闭。
7. Platform 健康后再启动 Worker 和前端，按部署手册做隔离数据验收。

## 审核用分文件

- `schema-v26.sql`：最终业务 DDL，不含建库、数据和 history。
- `seed-v26.sql`：必须的初始化数据。
- `flyway-baseline-v26.sql`：实际 Flyway baseline 的导出。
- `redis-governance-v26-init.sql`：以上整合后的直接执行文件。

**整合文件与分文件二选一，禁止重复执行。** 分文件方案由 DBA 创建/选中空库后依次执行 schema、seed、baseline。
表中的密码字段是结构，不含真实密码；种子数据仅来自版本控制中的 migration。
快照 BASELINE 记录的时间是生成时间，不是生产执行审计时间；DBA 应另保存发布执行记录。

## 生成与验证

本归档由当时版本的 `scripts/generate-database-baseline.mjs` 生成（需要 Node、JDK、Docker、unzip 和已构建的
Platform JAR 作为 Flyway/MySQL 客户端依赖来源）。当时脚本只取已提交 V1–V26；当前脚本已改为更新 sql/latest，不再生成本归档。
它创建一个仅发布回环临时端口的独立 MySQL 8.4 容器，迁移生成、导出、回灌、对比后删除该临时容器和卷。
不会连接现有项目 MySQL。临时目录只保存迁移和 Java 依赖，不写数据库口令。
临时 root/% 账号只供隔离容器内与回环 JDBC 验证，不是生产账号模板，不能复用。

验证覆盖：完整迁移链、完整 SQL 在另一空库初始化、全部业务表结构与数据对比、Flyway validate、
baseline 后 migrate 不重复执行 V1–V26。实际结果与文件校验和见 manifest；生产仍应先做 UAT 初始化验收。

当前快照只覆盖 V26，不包含 V27/V28 分析表；本次 Platform 发布启动时继续执行 V27/V28/V29。不能把当前快照描述为 V29 完整 SQL。
