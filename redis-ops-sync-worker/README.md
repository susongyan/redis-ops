# redis-ops-sync-worker

独立部署的 Redis 同步数据面，只包含同步协议和 Worker 运行时。它通过 MySQL 控制表和
`redis-ops-sync-contract` 与 Platform 协作，不依赖 Platform 业务源码。

```bash
mvn clean verify
```

部署入口：[测试/生产交付](../docs/deployment-delivery.md)，[外部配置模板](deploy/config/application-pro.yml.example)。
Worker 不执行 schema migration，必须等待 Platform 升级完成。
