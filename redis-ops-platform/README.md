# redis-ops-platform

Redis 治理控制面，包括 REST API、资产管理、任务控制、Flyway 和审计等能力。
构建前需要 Maven Registry 中存在 `io.github.redisops:redis-ops-sync-contract:0.1.0`。

```bash
mvn clean verify
```

部署入口：[测试/生产交付](../docs/deployment-delivery.md)。本项目包含 [数据库手册](sql/README.md)
和 [外部配置模板](deploy/config/application-pro.yml.example)。模板须填写后再部署。
