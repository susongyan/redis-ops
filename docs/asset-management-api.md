# Redis Cluster Asset Management API

所有接口前缀为 `/api/v1`。当前阶段不实现 RBAC；`X-Operator` 只用于记录操作人，不作为鉴权依据。所有写操作必须传递 `Idempotency-Key`；更新、删除资源还必须通过 `If-Match` 传递详情中的 `version`。同一操作人、接口和幂等键的重复请求返回首次结果，不重复修改资源或追加审计。

## 本地启动

```bash
docker compose up -d mysql redis
export REDIS_OPS_CREDENTIAL_KEYS="v1:$(openssl rand -base64 32)"
mvn -pl governance-bootstrap -am spring-boot:run     # 终端 1：API + Worker，端口 8080
cd frontend && npm install && npm run dev             # 终端 2：管理页面
```

Redis 密码随集群通过 write-only `password` 提交，平台使用 AES-256-GCM 加密后写入 MySQL，任何查询接口均不返回密码或密文。编辑已启用认证的集群时密码留空表示保持不变，关闭认证表示清除密码。

密钥环格式为 `v2:<base64-32-byte>,v1:<base64-32-byte>`，第一个 Key 是当前写入密钥。启动后的后台批处理会把旧 Key 密文重新加密到当前 Key；旧 Key 使用量归零后才可移除。主密钥只能通过进程环境变量提供，并且首次生成后必须在后续重启中稳定复用，不能为已有数据库重新生成。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST/GET | `/clusters` | 创建、筛选分页查询集群 |
| GET/PUT/DELETE | `/clusters/{id}` | 详情、乐观锁更新、软删除 |
| POST | `/clusters/connection-tests` | 校验待保存配置并实际测试 Redis 连通性 |
| POST/GET | `/clusters/{id}/discoveries` | 提交异步拓扑发现（202）、查看最近 100 次记录 |
| GET | `/clusters/{id}/nodes` | 查看最后一次成功拓扑快照 |
| GET | `/jobs/{id}` | 查询异步任务状态 |
| POST/GET | `/applications` | 创建、列出应用 |
| GET/PUT/DELETE | `/applications/{id}` | 详情及绑定、乐观锁更新、软删除 |
| PUT/DELETE | `/applications/{id}/clusters/{clusterId}` | 绑定、解绑集群 |
| POST/GET | `/regions` | 创建、筛选查询 Region |
| GET/PUT/DELETE | `/regions/{id}` | Region 详情、更新、软删除 |
| POST/GET | `/idcs` | 创建、筛选查询 IDC |
| GET/PUT/DELETE | `/idcs/{id}` | IDC 详情、更新、软删除 |
| GET | `/audits` | 按操作人、资源类型和资源 ID 查询审计日志 |

创建集群示例：

```json
{
  "name": "order-cache-prod",
  "environment": "prod",
  "businessLine": "order",
  "owner": "order-team",
  "opsOwner": "redis-ops",
  "serviceLevel": "P1",
  "mode": "CLUSTER",
  "endpoint": "10.0.0.10:6379",
  "authEnabled": true,
  "username": "redis-reader",
  "password": "write-only",
  "idcId": 1
}
```

每个集群维护一套连接账号，发现、同步、治理和访问层统一使用。`authEnabled=false` 表示无认证；用户名为空表示 requirepass/default 用户，填写用户名表示 ACL 认证。集群详情只返回认证状态、用户名、认证类型和密码是否已配置。

`endpoint` 支持多个 Seed，按顺序连接直到成功。Standalone/Cluster 使用 `host1:port,host2:port`，Sentinel 使用 `masterName@sentinel1:26379,sentinel2:26379`。发现请求只负责创建持久化任务并返回 `202 Accepted`；同进程 Worker 仅携带 `clusterId`，运行时从集群秘密表读取连接信息，通过数据库租约领取、失败重试，并在成功后原子刷新节点快照。设置 `WORKER_ENABLED=false` 后不领取任务，但资产 CRUD 仍正常，积压任务会在启用 Worker 后继续处理。

### 配置校验与连通性测试

连通性测试会先校验 Endpoint 格式，再实际执行平台拓扑发现所需的命令，而不只是检查
TCP 端口：

- Standalone：读取 server 和 replication 信息。
- Cluster：连接任一 Seed 并执行 `CLUSTER NODES`。
- Sentinel：连接任一 Sentinel 并查询指定 master 和 replicas。

编辑已有集群时可以提交 `clusterId`。如果密码留空，服务端会临时使用该集群已经保存的
密码；测试结束后清空密码内存，不会修改或新增资产数据。成功响应只返回部署模式、发现
节点数和耗时。失败时区分 Endpoint 格式、网络连接、认证失败，以及部署模式或 ACL
命令权限不匹配。

完整验收可执行：

```bash
mvn test
cd frontend && npm run build
./scripts/asset-smoke.sh
```

`asset-smoke.sh` 使用独立 API 端口并自动清理验收数据，覆盖无认证 Standalone、ACL
Standalone、Sentinel 和三主节点 Cluster。它还验证重复写入与发现不会产生重复结果、发现失败
不会覆盖最后一次成功节点快照、错误密码返回认证错误，以及数据库/API/日志/审计中不出现测试密码。
