# 本地用户、角色和 JDBC 会话

状态：本地认证及用户管理已实现，企业 OIDC / LDAP 仅预留端口，没有企业登录入口。
详见 [ADR-019](adr/ADR-019-local-identity-and-authentication-extensions.md)。

## 首次部署

数据库升级到 V31，空库使用 sql/latest/redis-governance-init.sql。所有 Platform 连接同一
MySQL，使用相同 Cookie 配置；不需要 Redis 或粘性会话。

通过受保护的 Spring 外部配置提供 identity.bootstrap.username（默认 admin）、
identity.bootstrap.password（无默认值），identity.cookie-secure 生产保持 true。
首次初始化后必须修改密码，并从外部配置删除初始密码。初始化只执行一次，重启不会覆盖账号。
仅本机 HTTP 联调设置 cookie-secure=false。配置可由 Spring Config Data / Apollo 注入。

## 账户和权限

- 本地登录名 3–64 位 ASCII 字母、数字、点、下划线、连字符，首字符字母或数字，统一小写。
- 密码 12–72 UTF-8 字节，只存 BCrypt 哈希。创建/重置后强制改密，不能访问业务功能。
- ADMIN 管理用户及运维；OPERATOR 仅运维。危险操作原有预检查和确认保留。
- 用户只启用/禁用，不物理删除，不修改登录名，不做集群级权限。
- 最后一个可用本地管理员不能禁用或降级，数据库事务锁防止并发绕过。
- 会话空闲 30 分钟、绝对 8 小时；HttpOnly Cookie，不在前端存 JWT 或密码。
- 禁用、角色修改、密码变更递增授权版本，各实例下一次请求拒绝旧会话。
  已提交后台任务不会自动取消；修改自己资料后也需重新登录。
- 数据库不可用时拒绝认证访问，不降级匿名。
- 每账号 5 次失败 / 5 分钟，每 IP 30 次尝试 / 5 分钟，跨实例共享计数。
  使用连接 IP，不信任未经验证的转发头；共享反向代理下需评估共用 IP 配额。

## 接口及部署适配

GET /api/v1/auth/csrf 获取 Cookie 和 data.token，写请求带 data.headerName 指定的头。
POST /api/v1/auth/login 接收 username/password；登录更新会话 ID，写请求重新获取 CSRF。
GET /api/v1/auth/me 返回当前用户；POST /api/v1/auth/logout 销毁会话。
POST /api/v1/auth/password 接收 oldPassword/newPassword，要求 If-Match 用户版本，成功后重新登录。

GET/POST /api/v1/users 查询/创建用户，PATCH /api/v1/users/{id} 更新，
POST /api/v1/users/{id}/reset-password 重置。
创建参数 login/displayName/role/password；更新 displayName/status/role；重置 password。
写请求要求 Idempotency-Key，更新/重置要求 If-Match。
密码不进入幂等摘要，同键重试以第一次成功设置的密码为准，不再次修改。
状态 ACTIVE / DISABLED，角色 ADMIN / OPERATOR。

业务匿名返回 401、权限不足 403。X-Operator 已不可信，审计使用 user:<内部ID>，
历史记录不改写。调用脚本必须登录并携带 Cookie / CSRF，不提供匿名后门。
Nginx / Vite 保持前后端同源，转发 /api/；跨站 VITE_API_BASE 不受首期支持。
管理端点仅无详情 health 匿名可用，其余默认拒绝；旧匿名 Prometheus/info 抓取需调整，
待明确独立管理入口授权方式后另行启用。

## 恢复与验证

建议预先创建第二个管理员。忘记密码由另一管理员重置；所有凭据丢失时需停服维护，
由 DBA 按审批恢复凭据并审计，不允许通过重启重建管理员。

node scripts/verify-identity.mjs 在隔离 MySQL 和两个临时 Platform 上验证会话共享、
CSRF、首次改密、角色限制、跨实例禁用、最后管理员保护及退出，结束清理临时容器。
node scripts/generate-database-baseline.mjs 从 migration 生成完整 SQL 并回灌验证。

资产验收脚本需要 ASSET_SMOKE_USERNAME / ASSET_SMOKE_PASSWORD；首次初始化另提供
ASSET_SMOKE_NEW_PASSWORD 完成强制改密。脚本使用同一 Cookie 和 CSRF，不输出密码。
分布分析隔离 UI 模式需提供 IDENTITY_BOOTSTRAP_PASSWORD；普通非 UI 集成不变。
