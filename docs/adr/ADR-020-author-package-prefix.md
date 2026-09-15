# ADR-020：作者命名空间

- 状态：Accepted
- 日期：2026-09-15
- 承接 ADR-014 的模块划分，仅替换命名空间。

Java 包与 Maven groupId 统一为 `io.github.susongyan.redisops`，Platform、Worker 和 sync-contract
分别保留其子包及 artifact 名称。源码目录、Spring 扫描、MyBatis 引用和测试同时迁移。
旧命名空间不提供转发兼容；历史 ADR 保留原文。

新契约坐标与旧坐标不同，需先安装或发布新 groupId 的 contract，再独立构建两个消费者。
三个后端构建须 clean，不能混用旧字节码。运行进程需要部署新 JAR 并受控重启。
不改变数据库表、JSON 字段、加密格式、同步状态和租约语义。回滚使用完整旧发布包，
无需数据库回滚。验证要求为契约 install、Platform 和 Worker 的 clean verify。
