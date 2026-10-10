# 只读执行记录删除

状态：已实现。设计见 [ADR-036](adr/ADR-036-read-only-record-deletion.md)。

风险扫描、Key 分布分析、独立数据校验和 AI 分析列表提供删除入口，需二次确认。TTL 治理、数据清理保持原有分页和完整执行记录；同步、Console、审计历史不受影响。

仅终态允许删除：风险 COMPLETED/FAILED/CANCELLED；分布 COMPLETED/INCOMPLETE/FAILED/CANCELLED；校验 PASSED/FAILED/INCONCLUSIVE/CANCELLED；AI COMPLETED。关联同步任务的校验不提供删除按钮。后台任务未结束时，接口返回 RECORD_EXECUTING，稍后重试。

## 接口

`DELETE /api/v1/execution-records/{type}/{id}`，type 仅支持 RISK_SCAN / KEY_DISTRIBUTION / VALIDATION / ANALYSIS。

需要已登录、CSRF、Idempotency-Key、If-Match（当前 version），成功返回 204。同一幂等键重放不会重复删除或审计；换键删除已删除记录返回 RESOURCE_NOT_FOUND。版本改变返回 CONCURRENT_MODIFICATION；非终态返回 RECORD_NOT_FINISHED；关联同步校验返回 RECORD_REFERENCED。不包含 TTL/清理类型。

## 数据与发布

V37 新增 key_distribution_task.deleted_at、validation_task.deleted_at、analysis_run.deleted_at/version；scan_task 使用已有 deleted_at/version。旧 migration 不变。AI 返回字段新增 version，旧不可变结果初始值为 0。

这是逻辑删除，不立即回收 MySQL 空间：已删除记录不出现在列表中，详情、分组、差异及控制接口均不可访问，关联结果仍保留用于内部引用，并遵循各模块原有保留周期（分布分析仍为 30 天）。审计记录类型、ID、终态及版本，并保存操作者账号与显示名快照，不保存扫描规则、Key、Value 或凭据。本功能不新增自动存储清理。

升级使用 Flyway V37；全新环境使用 sql/latest/redis-governance-init.sql。不要向已初始化数据库执行完整初始化包。无需 Worker 发布或新增配置，不执行任何 Redis 命令。

## 验证

Platform `mvn verify`、前端 39 项测试及构建通过。隔离 MySQL 验证 V1–V37 迁移、完整 SQL 回灌、Flyway 校验及四类记录的逻辑删除/版本冲突/查询过滤；验证分布 lease 和 DATA_VALIDATION Job 仍活动时被识别。页面检查桌面及 390px 窄屏、删除确认和右侧操作列；未删除现有演示记录。
