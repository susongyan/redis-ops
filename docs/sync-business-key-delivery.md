# 业务 Key 拆分同步：第四阶段交付

状态：代码已实现，未自动部署或重启现有服务。生产发布仍受本文末尾门禁限制。

## 页面和 API

- 新建任务提供包含 / 排除规则，排除优先；全量和增量使用相同不可变规则。
  每类最多 100 条，每条非空、最多 1024 UTF-8 字节。
- `*` 表示任意字节序列，`?` 表示一个字节，反斜杠转义；`[]` 为字面字符，不是正则。
  多字节中文不等于一个 `?`。扩大范围必须新建任务并建立全量基线。
- 示例 Key 只在当前浏览器计算，不查询 Redis、不提交 API、不写浏览器持久存储。
  最多 20 个、输入最多 16384 字符，关闭弹窗或停止编辑五分钟后清除。
  规则字节数乘样例字节数超过 200 万时停止预览并提示缩减；样例不是全量验证。
- 包含规则为空或仅星号通配全部时，页面要求明确确认；创建 API 必须传
  `confirmFullKeyspace: true`，否则返回 400。排除规则仍生效；这不替代启动时的写隔离与清空确认。
  外部调用方创建全范围任务时需同步升级请求字段，范围受限任务无需该确认。
- 新建页面显式默认 v3，可选择 v1 / v2。API 未提供 `commandPolicy.policyVersion` 仍按 v1。
  详情展示规则快照、策略版本和固定阻塞原因；能力查询传入所选策略版本。
- v2 限 Redis 6.2 / 7.x；v3 自契约 0.3.1 及对应 Worker 起增加 Redis 5.0。
  跨范围事务、跨 Slot 原子操作、未知命令和原始脚本仍失败关闭。

## 观测统计

Worker 已有 `/actuator/info` 的 `syncCommandObservations` 提供固定七项进程累计值：

| 字段 | 含义 |
| --- | --- |
| appliedSourceCommands | 已确认批次中的源业务命令数 |
| filteredSourceCommands | 已确认跳过的源业务命令数，包含过滤、非选中 DB 和内部保留范围 |
| expandedOrdinarySourceCommands | 普通源命令被规划为多条目标操作的次数 |
| targetOperations | 已确认计划中的目标业务操作数 |
| appliedTransactions | 已确认应用的源事务数 |
| skippedTransactions | 已确认整体跳过的源事务数 |
| blockedExecutions | 阻塞报告次数，不是去重后的命令数 |

没有 Key / 规则 / 任务 ID 标签，容量固定。成功计数在目标确认后增加；进程重启清零，
恢复和重试可能影响观测值，不能代替 checkpoint 或 MySQL 任务进度，也不是逐任务审计。
不新增公开管理端点；继续使用既有 Actuator 的网络隔离和权限配置。

## 升级与恢复

1. 暂停相关任务并核对目标 checkpoint / pending，保留 spool 和故障现场。
2. 发布 `sync-contract:0.3.1`，构建所有 Worker；确认能力声明支持 v1 / v2 / v3 后再升级 Platform。
   Redis 5 任务还需确认全部 Worker 的 `syncCapabilities.redis5Transactions=true`。
   不能混用尚无策略版本领取过滤的旧 Worker。
3. 最后发布前端，避免新页面向旧后端提交 v3 或全范围确认字段。
4. 历史 v1 / v2 任务不自动升级策略，不原地扩大规则；用隔离目标新建任务试迁移。
5. `BLOCKED_TARGET_BATCH_UNCONFIRMED` 需要核实目标实际执行结果，禁止删除 pending 后盲目重放。
   确认协议与故障处理见[阶段一记录](sync-target-batch-confirmation.md)。
6. 回滚需保留能识别当前策略和 pending 的 Worker。不能让 v1 Worker 领取 v3，也不能删除
   fence / checkpoint 绕过保护；无法证明兼容时保持暂停并向前修复。

本次没有 schema 变更，不新增或重写 migration。最新初始化包仍为 V33：
`redis-ops-platform/sql/latest/redis-governance-init.sql`，不是额外执行历史迁移的起点。

## 验收记录与复现

2026-09-17 开发环境：JDK 21 编译目标 17；contract / Platform / Worker 独立 Maven 构建，
前端 `npm ci`、Node 测试及构建。前端浏览器检查 1280 桌面和 390 像素窄屏，
包含 / 排除示例结果、规则换行与弹窗操作可见；未向既有后端创建测试任务。

- Redis 7.4 Cluster 三种拓扑转换集成测试 3 项通过，包含暂停期间 Lua effects、
  恢复及更高 generation 接管后非幂等命令不重复执行。
  正常恢复用例关闭前先暂停等待确认边界；重复测试曾在直接关闭时观察到正确的 pending 阻塞，
  不能将“目标值已可见”当作“checkpoint 已确认”。未确认恢复另由批次故障测试验证。
- Redis 6.2.14 / 7.4 Standalone 各两项完整流程通过，验证全量、MULTI / Lua effects、
  暂停恢复及 generation 接管；包含 / 排除对全量增量一致，RENAME / BITOP 生效，
  混合范围 Lua effects 整组阻塞且目标无部分写入。独立 ACL 用例未在本轮启用。
- `SourceTransactionAssemblerTest` 在 `-DargLine=-Xmx128m` 下通过：10,000 个事务、
  100 万条业务命令逐组释放缓冲。这是组装器压力测试，不是整个 Worker 的 retained heap 测量。
- 前端 18 项 Node 测试通过。安装仍报告已有依赖风险（1 moderate / 1 high），
  构建仍提示 Ant Design chunk 较大；本轮未自动升级依赖或改动打包策略。
- 隔离 MySQL 8.4 回灌最新完整 SQL，再用 Flyway 校验 V33：当前版本 33，无迁移重跑。
- 阶段二 / 三真实 Redis 6.2 和 7.4 命令、事务及批次故障测试见各阶段记录。

以下测试会清空隔离测试实例，**不得指向已有业务 Redis**：

```bash
# 仅在专用测试机执行；脚本自行创建并清理具名临时容器
SYNC_IT_POLICY_VERSION=v3 bash scripts/sync-cluster-it.sh

# 需预先创建专用 Redis 于 127.0.0.1:6390 / 6391，测试内部执行 FLUSHALL
REDIS_SYNC_IT=true SYNC_IT_POLICY_VERSION=v3 \
  mvn -f redis-ops-sync-worker/pom.xml -pl sync-service -am \
  -Dtest=StandaloneSyncTaskRunnerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

仍未完成的生产门禁：真实 Sentinel / Cluster 主从切换、MOVED / ASK / 在线迁槽、
持续高吞吐与多通道受限堆、生产网络故障矩阵。当前编码容量限制不等于 retained heap 上限，
不得宣称整个 JVM 不会 OOM；不能用数据校验代替复制语义与故障验收。
