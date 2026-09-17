# ADR-026 Redis 5.0 源事务同步

状态：Accepted，Redis 5.0.14 开发验证通过；未自动部署。

## 背景与决策

v3 已有有界 MULTI/EXEC 组装和目标批次确认协议，但版本门禁仅接受 Redis 6.2 / 7.x。
本次将显式 v3 扩展到 Redis 5.0；v1 默认与 v2 的原有版本范围不变。
契约发布为 0.3.1，Platform 和 Worker 同步升级。预检查与运行前所有主节点的实际 INFO
版本检查共用契约判断，不仅放宽页面或资产登记版本。

Redis 5 默认使用 Lua effects 复制；收到具体写命令及 MULTI/EXEC 时按已有事务协议执行。
若配置改为脚本原文传播，EVAL/EVALSHA 仍阻塞，不在目标执行脚本，也不修改源端配置。
参考：[Redis 官方 Lua 复制说明](https://redis.io/docs/latest/develop/programmability/eval-intro/)。

## 不变的安全边界

- 事务完整 EXEC 后才允许提交；混合过滤范围、范围外输入依赖、跨 Slot 事务整体阻塞。
- Redis 5 不存在的较新命令并不因此成为 Redis 5 命令；禁止新版源向旧版目标迁移。
- 保持 10,000 条 / 16 MiB 事务限制、租约、generation、fence、pending 和确认 checkpoint。
- 执行结果不确定不能自动重放；不引入 schema 或运行服务，不改变控制面所有权。
- 先升级全部 Worker 再创建 Redis 5 v3 任务；旧 0.3.0 Worker 仍会阻塞 Redis 5。

## 验证

使用隔离 Redis 5.0.14 验证真实复制流、全量增量、MULTI / Lua effects、业务范围过滤、
暂停恢复和 generation 接管；补充版本门禁及脚本原文失败关闭测试。
不以这些测试代替主从切换、在线迁槽和生产持续负载验收。
