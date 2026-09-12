# redis-ops-frontend

独立的 React/Vite 前端仓库。构建产物为 `dist/` 静态文件，可由 Nginx 托管并反向代理 API。

```bash
npm ci
npm run build
```

部署说明：[Nginx 与后端接口地址](../docs/frontend-deployment.md)。默认无需在构建时指定后端地址，
测试和生产可使用同一 dist，在 Nginx 配置 Platform 地址。
