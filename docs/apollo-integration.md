# Apollo 接入说明

当前项目未引入 Apollo，需要时由研发添加以下依赖和配置。配置按重启生效管理，无需新增 profile 或开关。

## 1. Maven 依赖

在 Platform 的 [bootstrap/pom.xml](../redis-ops-platform/bootstrap/pom.xml) 和 Worker 的
[sync-service/pom.xml](../redis-ops-sync-worker/sync-service/pom.xml) 中添加：

```xml
<dependency>
    <groupId>com.ctrip.framework.apollo</groupId>
    <artifactId>apollo-client-config-data</artifactId>
    <version>${apollo.version}</version>
</dependency>
```

在两个项目各自父 POM 的 `<properties>` 中定义版本：

```xml
<apollo.version>REPLACE_WITH_APPROVED_VERSION</apollo.version>
```

将占位符替换为公司批准、与本项目 Spring Boot 3.3.5 验证兼容的版本，然后重新构建。
适配器已包含 `apollo-client`，无需重复添加，也无需添加 `@EnableApolloConfig` 或配置 `apollo.bootstrap.enabled`。

## 2. 参数配置

在现有外部 `application-pro.yml` 中合并以下内容，不新增 Apollo 专用配置文件。
已有 `spring:` 节点时，将 `config.import` 合并到该节点下。

Platform 示例：

```yaml
app:
  id: redis-ops-platform
apollo:
  meta: https://REPLACE_APOLLO_META
  cluster: default
spring:
  config:
    import: "apollo://application"
```

Worker 示例：

```yaml
app:
  id: redis-ops-worker
apollo:
  meta: https://REPLACE_APOLLO_META
  cluster: default
spring:
  config:
    import: "apollo://application"
```

| 参数 | 含义 |
| --- | --- |
| `app.id` | Apollo 中创建的应用 ID，替换为各进程实际使用的 App ID。 |
| `apollo.meta` | 对应环境的 Apollo Meta Server 地址，用于发现 Config Service。 |
| `apollo.cluster` | Apollo 配置集群名，使用默认集群时填 `default`。 |
| `spring.config.import` | 要加载的 Namespace，默认使用 `apollo://application`；`apollo://` 也是加载默认 `application` 的简写。 |

两个进程分别读取各自 `app.id` 下的 `application` Namespace。

需要加载多个 Namespace 时，使用 YAML 列表，每项都带 `apollo://` 前缀：

```yaml
spring:
  config:
    import:
      - "apollo://application"
      - "apollo://your-extra-namespace"
```

将 `your-extra-namespace` 替换为实际名称，并确保对应 Namespace 已发布且应用有权读取。
同名属性以后面的导入项为准；上例中 `your-extra-namespace` 覆盖 `application` 中的同名属性。
也可以写成逗号分隔的字符串：`"apollo://application,apollo://your-extra-namespace"`。

引导参数保留在本地部署配置中，不能只放在待连接的 Apollo Namespace 中。
沿用现有启动命令；Spring 的 `pro` profile 不会自动选择 Apollo 环境，应配置对应环境的 Meta Server。

## 3. Namespace 中的配置

创建并发布 **properties 类型 Namespace**，使用应用原有的 Spring 属性名。例如：

```properties
spring.datasource.url=jdbc:mysql://REPLACE_MYSQL_HOST:3306/redis_governance?serverTimezone=UTC
spring.datasource.username=REPLACE_DB_USERNAME
spring.datasource.password=REPLACE_DB_PASSWORD
redis-ops.credential.keys=v1:REPLACE_BASE64_32_BYTE_KEY
```

| 配置 | 含义 |
| --- | --- |
| `spring.datasource.url` | MySQL 连接地址，保留 `serverTimezone=UTC`；两个进程连接同一个业务库。 |
| `spring.datasource.username` / `password` | 各进程自己的数据库账号和密码。 |
| `redis-ops.credential.keys` | 凭据加密密钥环，格式为 `keyId:Base64密钥`，多个用逗号分隔；每个密钥解码后为 32 字节，两侧配置一致。 |

其他配置按 [Platform 模板](../redis-ops-platform/deploy/config/application-pro.yml.example) 和
[Worker 模板](../redis-ops-sync-worker/deploy/config/application-pro.yml.example) 展平为同名属性即可。
已迁入 Apollo 的属性避免在本地重复配置；配置发布后受控重启相关进程生效。

参考：[Apollo 官方 Java 客户端指南](https://github.com/apolloconfig/apollo/blob/master/docs/zh/client/java-sdk-user-guide.md)。
