# 前端独立部署与后端地址

## 默认：一次构建，各环境配置 Nginx

`src/api.js` 默认请求相对路径 `/api/v1/...`，不在构建时绑定 Platform 地址。
显式清空构建参数，避免环境或 `.env*` 残留值：

```bash
cd redis-ops-frontend
npm ci
VITE_API_BASE='' npm run build
```

部署 `dist/` 全部内容到 `/opt/redis-ops-frontend/dist/`。测试和生产使用同一静态包，仅改代理。
以下 server 示例放在公司 HTTPS/认证网关后，替换后端主机与 root：

```nginx
server {
    listen 8088;
    server_name _;
    root /opt/redis-ops-frontend/dist;
    index index.html;
    location /api/ {
        # 不加末尾 /，保留 /api/v1/...。
        proxy_pass http://platform.internal:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    # 当前采集页面固定请求同域此端点；由外部入口限制身份/网络访问。
    location = /actuator/prometheus {
        proxy_pass http://platform.internal:8080;
    }
    location /actuator/ { return 404; }
    location / { try_files $uri $uri/ /index.html; }
}
```

先 `nginx -t`，成功后 reload；检查页面、API JSON 返回值和指标访问权限。
此示例没有配置 HTTPS 证书和认证，必须由公司入口补齐；客户端 X-Operator 不是可信身份凭证。
浏览器只连接 Nginx，Nginx 连接 Platform，前端不直接调用 Worker。

使用旧部署脚本时，在安装目录 `conf/redis-ops.env` 设置
`PLATFORM_PROXY_URL='http://platform.internal:8080'`，由脚本生成配置后重启该前端角色。
旧模板代理整个 `/actuator/`，生产应收紧为按需端点；不要同时管理手写站点与脚本生成站点。

## 可选：构建时写死地址

```bash
VITE_API_BASE='https://api.example.internal' npm run build
```

地址固化进 JS，部署机环境变量不能改变已生成 dist，修改需重新构建。不要加 `/api` 或末尾 `/`。
跨域需审核 CORS（含 If-Match、Idempotency-Key 等请求头），HTTPS 页面不得调用 HTTP 后端。
当前 `/actuator/prometheus` 不使用 VITE_API_BASE，仍需同域代理，因此推荐默认方式。
前端配置公开可读，不得存数据库密码、Apollo 凭据或 Redis 密钥。

## 本地开发

`npm run dev` 通过 vite.config.js 把 /api、/actuator 转到 localhost:8080。
Vite 开发代理不会进入静态产物，部署后必须配置 Nginx/网关。

返回 [部署交付入口](deployment-delivery.md)。
