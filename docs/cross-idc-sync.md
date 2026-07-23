# 跨机房集群关系与同步

## 资源模型

Region 和 IDC 是独立资源，集群通过 `idcId` 关联机房，Region 由 IDC 推导。长期容灾使用主备关系；迁移或一次性同步任务可以不创建关系，直接指定源、目标集群。

主备关系要求两个 ACTIVE 集群位于不同 IDC、部署模式一致，并且 Redis 主版本兼容。关系当前方向为 `primaryClusterId → standbyClusterId`，目标 RPO 由 `desiredRpoSeconds` 定义。

RPO 的时间戳水位、Offset 估算、Backlog 追平时间和切换判定规则见 [Redis 同步 RPO 计算与切换判定](rpo-calculation-and-switchover.md)。

## 受控切换

只有最新同步任务为 `CAUGHT_UP` 且 RPO 达标时才能发起切换。平台停止旧方向后进入 `WAITING_EXTERNAL_SWITCH`；业务流量由外部系统切换，操作人确认后平台交换主备并创建反向同步任务。确认前取消会恢复原方向并创建恢复任务。

当前状态推进 API 是同步工具适配器边界，尚未绑定具体复制工具，也不会自动修改 DNS、代理或应用配置。

## Endpoint Seed

- Standalone/Cluster：`host1:6379,host2:6379,host3:6379`
- Sentinel：`masterName@sentinel1:26379,sentinel2:26379`

拓扑发现按顺序尝试 Seed；任一地址可连接即可读取完整拓扑。

## API

- `/api/v1/regions`、`/api/v1/idcs`：机房字典 CRUD。
- `/api/v1/cluster-relations`：长期主备关系 CRUD 和详情。
- `/api/v1/sync-tasks`：长期或临时同步任务及事件。
- `/api/v1/cluster-relations/{id}/switchovers`：发起受控切换。
- `/api/v1/switchovers/{id}/confirm|cancel`：确认或取消外部流量切换。

RBAC 暂不实现；`X-Operator` 只用于审计。
