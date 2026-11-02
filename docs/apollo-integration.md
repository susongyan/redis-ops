# Apollo 接入说明（可选接入）

Apollo 是**可选**的外部配置源：依赖已在 classpath（`apollo-client` 与
`apollo-client-config-data` 2.2.0），默认 `application.yml` 的
`spring.config.import` 携带 `optional:apollo://application`。未配置
`app.id` / `apollo.meta` 时该导入被跳过（仅 WARN 日志），进程使用
本地文件与环境变量配置正常启动；`ApolloOptionalConfigImportTest`
（Platform 与 Worker 各一）固定守护这一行为。

## 1. 启用

为对应进程提供以下参数（任选：环境变量、JVM 参数、`server.properties`
或 classpath 的 `META-INF/app.properties`）：

```yaml
app:
  id: redis-ops-platform        # Worker 侧使用自己的 App ID
apollo:
  meta: https://REPLACE_APOLLO_META
  cluster: default
```

| 参数 | 含义 |
| --- | --- |
| `app.id` | Apollo 中创建的应用 ID，Platform 与 Worker 各自一个。 |
| `apollo.meta`（或 `APOLLO_META`） | 对应环境的 Apollo Meta Server 地址，用于发现 Config Service。 |
| `env`（或 `ENV`） | Apollo 环境（DEV/FAT/UAT/PRO），启动时指定。 |
| `apollo.cluster` | 配置集群名，默认集群填 `default`。 |

提供后 `optional:apollo://application` 导入生效，无需修改代码或新增
profile，重启进程即可。要显式关闭，删除部署配置里的导入行。

需要加载多个 Namespace 时使用 YAML 列表，每项都带 `apollo://` 前缀：

```yaml
spring:
  config:
    import:
      - "optional:apollo://application"
      - "optional:apollo://your-extra-namespace"
```

同名属性以后面的导入项为准；也可以写成逗号分隔的字符串。

## 2. Namespace 中的配置

创建并发布 **properties 类型 Namespace**，使用应用原有的 Spring 属性名。例如：

```properties
spring.datasource.url=jdbc:mysql://REPLACE_MYSQL_HOST:3306/redis_governance
spring.datasource.username=REPLACE_DB_USERNAME
spring.datasource.password=REPLACE_DB_PASSWORD
redis-ops.credential.keys=v1:REPLACE_BASE64_32_BYTE_KEY
```

| 配置 | 含义 |
| --- | --- |
| `spring.datasource.url` | MySQL 连接地址，**不携带会话时区参数**，会话时区跟随数据库服务器（见 [ADR-037](adr/ADR-037-database-session-timezone.md)）；两个进程连接同一个业务库。 |
| `spring.datasource.username` / `password` | 各进程自己的数据库账号和密码；Worker 专用账号密码不应落在明文 env 文件里。 |
| `redis-ops.credential.keys` | 凭据加密密钥环，格式为 `keyId:Base64密钥`，多个用逗号分隔；每个密钥解码后为 32 字节，两侧配置一致。 |

`@Value` 与 `@ConfigurationProperties` 绑定的 Bean 默认支持 Apollo 发布后
热更新，无需额外代码。若某配置项不期望热更，在 Apollo 中不做该 key，
或改用环境变量注入。

引导参数（`app.id`、`apollo.meta`、`env`）保留在本地部署配置中，不能只放在
待连接的 Apollo Namespace 里。其他配置按
[Platform 模板](../redis-ops-platform/deploy/config/application-pro.yml.example) 和
[Worker 模板](../redis-ops-sync-worker/deploy/config/application-pro.yml.example)
展平为同名属性即可；已迁入 Apollo 的属性避免在本地重复配置。

参考：[Apollo 官方 Java 客户端指南](https://github.com/apolloconfig/apollo/blob/master/docs/zh/client/java-sdk-user-guide.md)。
