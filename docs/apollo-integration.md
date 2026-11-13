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

## 2. Apollo 与 Spring Profile：部署一律 profile-less

**接 Apollo 的部署不要激活任何 Spring profile。** 这不是建议，是 Config Data
的优先级规则决定的硬约束：

- **Apollo 拉到 ≠ Apollo 生效。** 激活的 profile（`application-{profile}.yml`，
  包内或外置）定义过的键，**永远压过 Apollo 发布值**——Apollo 拉取正常、日志无错，
  但那个键就是被本地 profile 遮蔽着，排查时极具迷惑性。
- 想让 Apollo 接管的键，任何 profile yml、任何环境的本地配置都不能再定义。
- 包内只随 Jar 发布一个 `application-dev.yml`（本地开发 overlay），且**仅在显式
  `--spring.profiles.active=dev` / `REDIS_OPS_PROFILE=dev` 时激活**；部署态
  profile-less，配置来源就是"基础默认值 + Apollo 发布值（或部署目录
  `config/application.yml`）"。没有第二个环境 profile，也不要自造。
- `${DB_URL:默认}` 这类占位符**不挡 Apollo**：键级覆盖发生在占位符解析之前，
  Apollo 发布 `DB_URL` 即可生效。

## 3. 优先级（从高到低，实测验证）

1. 命令行参数 `--key=v`
2. JVM `-D` 系统属性 / OS 环境变量
3. 激活 profile 的 `application-{profile}.yml`（包内/外置）——**冲突键上压过 Apollo**
4. **Apollo 发布值**
5. `./config/` 外置非 profile 配置
6. 基础 `application.yml` 默认值

注意第 3 与第 5 的差别：profile 文件在 Apollo 之上、普通 `./config/application.yml`
在 Apollo 之下。所以"本地放一份兜底、Apollo 管发布"的模型是安全的（Apollo 赢），
而"再建一个环境 profile"的模型会静默遮蔽 Apollo（profile 赢）。

## 4. 可选性与降级

未设置 `APOLLO_META` 时进程**不会启动失败**：apollo-client 退到默认 meta
`http://apollo.meta`，解析失败后由后台长轮询线程按 32s+ 退避打 WARN
（`Could not load config ... Return default value now`），应用照常以本地默认值
启动。后果是漏配 meta 时**静默**使用 application.yml 默认值——显式故障形态
如数据源落到 `localhost:3306`。因此部署验收必须核对配置生效来源
（启动横幅/健康检查/实际数据源），不能只看"进程起来了"。

## 5. Namespace 中的配置

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
[Platform 模板](../redis-ops-platform/deploy/config/application.yml.example) 和
[Worker 模板](../redis-ops-sync-worker/deploy/config/application.yml.example)
展平为同名属性即可；已迁入 Apollo 的属性避免在本地重复配置（重复配置的本地
profile 文件会按第 3 节优先级遮蔽 Apollo）。

参考：[Apollo 官方 Java 客户端指南](https://github.com/apolloconfig/apollo/blob/master/docs/zh/client/java-sdk-user-guide.md)。
