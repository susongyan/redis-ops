# Apollo 应用侧接入（尚未实现）

已实现：两个进程读取 Spring 属性，支持外部 YAML，现有 AES-GCM 加密逻辑不变。
未实现/未验收：Apollo 客户端依赖、生产连接与统一热更新。当前 JAR 不能仅设置
`spring.config.import=apollo://...` 就算接入成功。本文件是后续接入说明，不建设 Namespace/CI/私服。

## 后续应用改动

Apollo 官方有 Config Data 适配器。后续在 Platform bootstrap 和 Worker sync-service 各自引入，
不放进 contract/domain；在父 POM 固定公司批准、与本项目 Spring Boot 验证兼容的 `${apollo.version}`：

```xml
<dependency>
    <groupId>com.ctrip.framework.apollo</groupId>
    <artifactId>apollo-client-config-data</artifactId>
    <version>${apollo.version}</version>
</dependency>
```

适配器已包含客户端依赖。引入并验证后才可用以下本地引导配置：

```yaml
app:
  id: redis-ops-platform
apollo:
  meta: https://REPLACE_APOLLO_META
  cluster: default
spring:
  config:
    import: "apollo://redis-ops-platform-pro"
```

Worker 使用自己的 app.id 和 `apollo://redis-ops-worker-pro`。名字只是约定示例，由公司提供实际值。
Spring profile=pro 不会自动选择 Apollo 环境。不要混用 bootstrap 与 Config Data 两套加载方式。
加载机制依据 [Apollo 官方 Java 客户端指南](https://github.com/apolloconfig/apollo/blob/master/docs/zh/client/java-sdk-user-guide.md)。

## Namespace 内容与引导信息

properties Namespace 将两套 YAML 展平为同名 Spring 属性：

| 属性 | Platform | Worker |
| --- | --- | --- |
| spring.datasource.url / username / password | Platform 账号 | 同库 Worker 账号 |
| spring.flyway.enabled | 按迁移方案 | false |
| spring.flyway.user / password | 若启动时迁移则设置 | 不设置 |
| redis-ops.credential.keys | 完整密钥环 | 同一完整密钥环 |
| server.port | 8080 示例 | 8081 示例 |
| sync.engine.* | 不设置 | 并发、租约、spool 等 |

不要求使用 DB_PASSWORD/REDIS_OPS_CREDENTIAL_KEYS 等旧环境变量名。
app.id、配置服务发现地址、首次连接需要的访问凭据必须在读取远端前取得，不能仅放在待连接 Namespace。
避免在命令行、环境变量、本地 YAML 和 Apollo 配置同一属性为不同值。
配置中心负责权限、审计、存储安全；客户端缓存也可能包含秘密，应按公司缓存保护策略处理。

## 生效与验收

密钥对象、数据源及引擎参数在启动时构造，当前未实现统一动态重绑定。
默认按“发布配置 → 暂停/结束任务 → 受控重启 → 验收”处理，不承诺发布即热更新。
轮换先让所有读者持有新旧 key，再切换新写入 key，旧密文迁移完成前不能删旧 key。
配置中心回滚不是密文/数据库回滚。MySQL 仍保存加密后的 Redis 凭据，不将密码放入 Job payload。

接入验收覆盖：首次无缓存启动、配置服务不可达、缓存回退策略、Namespace 缺失、无权限、
配置缺项、两侧密钥一致及重启生效；错误日志不能暴露秘密。不要仅凭缓存可用宣称具备生产容灾。
本轮未做 Apollo 联调，不承诺某个公司客户端版本可直接投入生产。

返回 [部署交付入口](deployment-delivery.md)。
