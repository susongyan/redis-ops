# 同步任务的 Worker 与机器 IP

同步任务列表和详情展示任务 ID、租约 Worker 实例 ID、机器 IP、最近心跳及租约状态。
页面每 5 秒批量查询 Platform；不直连 Worker，不探测机器，不改变同步控制和 fencing 行为。

## 配置

Worker Spring 配置：

```yaml
sync:
  engine:
    instance-id: worker-pro-01
    worker-ip: 10.0.0.12
```

`worker-ip` 也支持环境变量 `SYNC_ENGINE_WORKER_IP`。留空尝试获取本机 IP；不能识别或识别到
回环、通配、链路本地地址时不报告。显式配置必须是合法的非回环单播 IPv4/IPv6，不接受主机名。
多网卡、Docker/Kubernetes 建议由部署配置注入可识别的机器 IP；自动获取可能是容器 IP，不能保证是宿主机 IP。
同机多个 Worker 使用不同 instance-id、端口和 spool 目录；实际实例 ID 还会追加进程 UUID。
配置修改后重启 Worker，在下一次领取任务时上报，不会主动迁移已有任务。

## 接口与状态

`GET /api/v1/sync-task-workers?taskIds=1,2`，一次 1–100 个正整数 ID，重复 ID 去重，不存在的任务不返回。
沿用 API envelope，data 为数组，字段为 `taskId/runtimeId/leaseOwner/workerIp/phase/heartbeatAt/leaseUntil/leaseStatus`。
数据库批量 LEFT JOIN，按数据库 UTC 时间判定 `VALID`、`EXPIRED`、`UNASSIGNED`，无 runtime 的任务也返回未分配。
前端同时检查已知租约期限，避免旧快照持续显示有效；请求失败明确标记状态获取失败。

- 租约有效：此实例持有该任务租约，不代表机器健康或同步已追平。
- 租约过期：显示上次执行者，不断言已宕机或已开始接管。
- 未分配 / 已释放：没有租约持有者。
- IP 未上报：旧版 Worker、无法自动识别或混合版本接管，不能猜测机器地址。

## 升级

先部署 Platform，由新增 V29 migration 向 sync_runtime 增加可空 IP 和上报 runtime ID 字段；再部署 Worker，最后前端。
旧 Worker 可以继续运行，但不显示 IP。上报 runtime ID 与当前 runtime 不一致时隐藏 IP，防止旧版接管沿用历史地址。
新环境使用 sql/latest 中的 V29 完整初始化 SQL；历史 V26 快照保持不变，使用旧快照时须继续迁移到 V29。不要直接用新 Worker 连接未升级的库。
回滚代码时保留新增可空列，不回改已执行 migration。

验证：`node scripts/verify-analysis-migrations.mjs --worker-ip` 在独立临时 MySQL 中检查 V26 → V29、重复迁移、租约状态和混合版本接管。
