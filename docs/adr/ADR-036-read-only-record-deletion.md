# ADR-036：只读执行记录删除

状态：Accepted，已实现。

范围仅为风险扫描、Key 分布分析、独立数据校验、AI 分析的已结束记录。TTL 治理、数据清理、同步任务和 Console 历史不增加删除能力。

由 Platform 提供删除用例及领域持久化端口，Infrastructure 只操作 MySQL。采用逻辑删除：增加 deleted_at（风险任务已有该字段），列表、详情、结果和控制接口过滤删除记录；保留关联结果、审计和外部引用，不声称回收数据库空间。AI 结果原为不可变记录，补 version 用于统一 If-Match 语义。

允许的终态：风险 COMPLETED/FAILED/CANCELLED；分布 COMPLETED/INCOMPLETE/FAILED/CANCELLED；独立校验 PASSED/FAILED/INCONCLUSIVE/CANCELLED；AI COMPLETED。运行、排队、暂停、草稿不可删除。关联同步任务的校验保留，避免丢失同步验证证据。

删除使用 Idempotency-Key、If-Match、事务和资源行锁。分布任务先锁集群 runtime 再锁任务，与 checkpoint/控制动作锁顺序一致；仍有 owner/租约的任务拒绝删除。风险/校验还有 PENDING/RETRY/RUNNING Job 时拒绝删除，结束后重试。开始、重试和扫描查询过滤 deleted_at；审计与删除同事务提交，失败完整回滚。审计仅记录类型、ID、状态和版本，不记录 Key、规则、Value 或凭据。

页面删除需明确确认，成功后刷新列表并关闭对应详情；不执行 Redis 命令。不提供批量删除、自动删除或普通恢复入口。迁移仅新增 V37，旧记录默认未删除；最新完整 SQL 通过隔离 MySQL 迁移、回灌及 Flyway 校验重新生成。
