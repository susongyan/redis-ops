# 本地核心流程实测（2026-09-12）

## 运行环境

- Frontend：`http://127.0.0.1:5173/`，HTTP 200。
- Platform：8080，`/actuator/health` 为 UP。
- Sync Worker：8081，`/actuator/health` 为 UP。
- MySQL：现有 Colima 项目容器，schema 已到 V28。
- 单机源/目标：现有项目 Redis 6380 / 6381。
- 新增 `redis-ops-local-demo-cluster`：7401、7402、7403 三主节点，16384 槽完整覆盖。
  端口只绑定宿主机回环；无副本，仅用于本地功能验证，不是生产高可用配置。

未操作其他 benchmark Redis/Valkey 容器。

## 已通过的流程

| 链路 | 资产 ID（源/目标） | 目标 DB | 同步任务 | 校验任务 | 风险任务 |
| --- | --- | --- | --- | --- | --- |
| Standalone → Standalone | 5 / 6 | 0 | 2：FINISHED | 1：PASSED | 1：COMPLETED |
| Cluster → Standalone | 9 / 10 | 3 | 5：FINISHED | 2：PASSED | 2：COMPLETED |

两条链路均通过真实 REST API 发现资产、同步预检查、显式确认启动、全量复制、增量写入、暂停、
暂停期间目标不变、恢复追平和确认结束。全量阶段包含 string/hash/list/set/zset 五种类型，
24 条初始数据；另有 1 条增量数据。最终 FULL 校验通过；两次风险扫描均扫描 25 条数据，
发现 25 条无 TTL、0 条大 Key。Cluster 同步创建了 3 个源通道。

演示操作使用 `local-demo` 操作者；最终可查询到 65 条演示审计记录。
只在启动前确认空的目标 DB 上，通过系统预检查和确认流程授权重置。
DB1 / DB2 及失败任务 3 / 4 保留为修复前现场；未删除演示数据。

## 实测修复

1. Worker Redis 连接适配器的 final 修饰导致 Spring Repository 类代理无法创建。
2. Worker 租约恢复查询文本块拼接缺少 SQL 分隔符，同时消除关联表同名列歧义。
3. Cluster Runner 同步报告阶段导致启动协调器重复迁移；改为重置成功后先发布状态再启动。
4. 恢复阶段状态发布与通道追平报告竞争；先发布恢复状态，再启用 Runner，异常时 abort 并失败关闭。

对应增加代理、MyBatis BoundSql、启动/恢复顺序、恢复失败版本推进、控制 Job 重试测试。
Worker 完整 `mvn spotless:apply verify` 通过；专用集成测试中 5 项仍因开关未启用而跳过，
本文 REST + Docker 实测不是对所有集成测试的替代。独立审查 agent 已复核上述修复。

## 本地运行文件

本轮日志、spool、临时启动密钥及摘要在 `/tmp/redis-ops-local.VmjCJ2/`。
密钥未写入本文、源码或测试输出；两个进程使用同一临时密钥。
Worker 从该目录的 worker.jar 运行，避免后续构建覆盖正在运行的 JAR。
本轮不包含生产部署、故障注入/租约接管压测或 Cluster 副本故障转移验证。
