# ADR-013：最小同步契约与独立仓库构建

- 状态：Accepted
- 日期：2026-09-12
- 决策者：项目维护者
- 关联契约：[架构契约](../architecture-contract.md)

## 背景

公司需要分别构建部署前端、Platform 和 Sync Worker。共享 Platform domain、application 或
infrastructure 会使 Worker 获得不必要的控制面依赖，并阻碍独立发布。

## 决策

三个运行项目分别拥有源码、构建入口和部署配置；另设纯 Java 17 sync-contract artifact，
只承载跨进程需要一致的状态、控制动作、命令能力与版本约定，不引入运行框架和凭据。
Worker 自有领域模型、MyBatis adapter 和凭据解密器，不调用 Platform Service。

数据所有权不变：Platform 写控制意图，Worker 通过 MySQL lease 领取任务；目标 Redis fence
与 checkpoint 是最终写入事实。数据库 migration 仍归 Platform；此次拆分不修改 schema。
AES-GCM 格式及 AAD 保持兼容，主密钥存储与注入由部署配置或外部配置中心负责。

## 迁移与恢复

使用可重复导出脚本生成四个独立目录，各消费者只引用固定发布版 contract，不复制其源码。
当前工作区仍保留 Monorepo 供联调；完成独立验证和公司内部建仓后，再切换生产构建入口。
导出是工作区快照，包含未提交源文件，发布前必须核对并提交各自仓库的代码。
契约先发布再升级消费者，已发布版本不得覆盖。未通过构建或兼容性验证时保留现有部署。

## 验证

先将 contract 发布到临时 Maven Registry，再从不同的空本地缓存分别构建消费者，检查源码
和依赖树边界。真实 Redis/MySQL 租约接管与同步集成验收单独执行，不用构建成功替代。
企业 Registry 地址、账号与 Apollo Namespace 的实际部署验证仍需对应外部环境。
