# ADR-014：项目内部模块和包命名

- 状态：Accepted
- 日期：2026-09-12
- 关联：[ADR-013](ADR-013-minimal-sync-contract-repositories.md)

物理拆仓后，Platform 使用 common/domain/application/infrastructure/api/bootstrap 子目录，
Maven artifact 为 redis-ops-platform-*；Worker 使用 sync-protocol/sync-service 子目录，
artifact 为 redis-ops-worker-protocol/service。Java 包分别归属 io.github.redisops.platform
与 io.github.redisops.worker，Platform 内置调度器归 platform.scheduling，Worker 引擎归 worker.runtime。

公开的 io.github.redisops.sync.contract 包和 artifact 保持不变。本次仅调整内部名称、扫描路径
与构建引用，不变更表结构、JSON 字段、密码加密格式、控制动作和租约/fence/checkpoint 语义。
历史 ADR 保留原名称以反映当时决策；新构建与运行文档使用新名称。

重复拆仓模板和根 formatter 移除，三个 Maven 根各自维护 formatter，导出直接复制实际代码根。
数据目录和已有发布包保留；回滚使用 Git 中的目录与包名改动，不需要数据库回滚。
