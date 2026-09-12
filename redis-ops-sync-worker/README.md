# redis-ops-sync-worker

独立部署的 Redis 同步数据面，只包含同步协议和 Worker 运行时。它通过 MySQL 控制表和
`redis-ops-sync-contract` 与 Platform 协作，不依赖 Platform 业务源码。

```bash
mvn clean verify
```
