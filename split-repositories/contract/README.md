# redis-ops-sync-contract

Platform 与 Sync Worker 之间的最小 Java 17 契约。该仓库只发布 Maven artifact，不运行服务，
也不允许引入 Spring、MyBatis 或 Redis 客户端运行时依赖。

```bash
mvn clean verify
```

发布由手动 CI 触发，Registry 地址和凭据由仓库 Secret 提供。
