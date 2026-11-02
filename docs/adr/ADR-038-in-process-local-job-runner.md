# ADR-038：用户触发的长任务在接收实例进程内执行

- 状态：已接受
- 日期：2026-11-02
- 关联契约：架构契约第 2、3 节

## 背景

校验、风险扫描、Key 分布分析和治理执行原由每类任务一个调度 JobWorker
（`ValidationJobWorker`、`RiskScanJobWorker`、`TtlGovernanceJobWorker`、
`CleanupGovernanceJobWorker` 等）从异步队列表领取执行。该模型的问题：

- 一次性 job worker 的生命周期与任务暂停/恢复、跨实例接管耦合弱，实例崩溃后
  任务状态长时间悬置；
- 排队掩盖了实例负载，任务在不可用实例上堆积而不可见；
- 每类任务一套 worker 样板，并发与超时约束分散。

## 决策

1. 用户触发的长任务（校验、风险扫描、Key 分布、治理 preflight/apply）由
   `LocalJobRunner` 在**接收请求的实例进程内**执行，不再入异步队列表。
2. 所有本地任务共享一个有界线程池（`platform.local-jobs.max-concurrent`，默认 2；
   `max-queued`，默认 16）。池满时提交被拒绝，外围事务回滚状态变更并返回
   "本实例忙"，而不是堆积不可见工作。
3. 崩溃容忍由**每页 run 心跳**（`local_run`/治理 run 心跳，Flyway V40/V42）加
   `LocalRunRecoveryWorker` 提供：恢复扫描将心跳过期的 run 判定失败，允许重试，
   不自动续跑部分写入。
4. Key 分布任务固定在创建它的实例上执行并锁分片会话；治理 preflight/apply 支持
   checkpoint 续跑。分布式扩展仍以 MySQL 乐观锁与任务版本为准，不改变数据所有权。
5. 旧的各任务 JobWorker 类与 `async_job` 领取路径删除；`platform.jobs.*` 仅保留
   轻量轮询与实例标识配置。

## 后果

- 少一层队列编排；任务启动即可见地占用本实例配额，饱和是显式失败。
- 实例重启会终止其上所有本地任务，由恢复 worker 统一收敛为失败态，操作员重试。
- 治理与扫描的并发上限受单实例线程池约束，跨实例水平扩容以任务为粒度自然分布。

## 落地与验证

`LocalJobRunnerTest`、`GovernanceExecutionTest`、`LocalRunRecoveryWorkerConfigurationTest`
覆盖池满拒绝、心跳过期收敛与恢复路径；`mvn verify` 于各 Maven 根执行。
