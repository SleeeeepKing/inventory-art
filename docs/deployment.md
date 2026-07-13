# 部署

系统由 Vue 静态前端、Spring Boot 后端、PostgreSQL 和可选的 S3 兼容商品图片存储组成。后端启动时由 Flyway 执行迁移，Hibernate 只验证结构。

## 生产环境变量

至少配置：

```dotenv
DATABASE_URL=jdbc:postgresql://host:5432/inventory_art
DATABASE_USERNAME=inventory
DATABASE_PASSWORD=strong-password
SPRING_PROFILES_ACTIVE=prod
DATABASE_POOL_SIZE=3
DATABASE_MIN_IDLE=0
DATABASE_CONNECTION_TIMEOUT_MS=10000
DATABASE_IDLE_TIMEOUT_MS=300000
DATABASE_MAX_LIFETIME_MS=900000
JWT_SECRET=at-least-32-random-characters
CORS_ALLOWED_ORIGINS=https://inventory.example.com
COOKIE_SECURE=true
STORAGE_PROVIDER=r2
R2_ENDPOINT=https://ACCOUNT_ID.r2.cloudflarestorage.com
R2_PUBLIC_ENDPOINT=https://ACCOUNT_ID.r2.cloudflarestorage.com
R2_REGION=auto
R2_ACCESS_KEY_ID=...
R2_SECRET_ACCESS_KEY=...
R2_BUCKET_PRIVATE=inventory-art
R2_PRESIGNED_URL_EXPIRATION_SECONDS=900
VITE_API_BASE_URL=https://api.example.com/api/v1
```

前端的 `VITE_API_BASE_URL` 是构建时变量。Railway 必须激活 `prod` profile，才能应用 `minimum-idle: 0` 等 Serverless 连接池配置。后端还支持 `JWT_ACCESS_TOKEN_MINUTES`、`JWT_REFRESH_TOKEN_DAYS` 和 `COOKIE_SAME_SITE` 等可选参数。

## 构建

```bash
cd backend && ./mvnw clean package
npm --prefix frontend ci
npm --prefix frontend run build
npm --prefix frontend run pwa:check
```

仓库包含前后端 Dockerfile 和 `docker-compose.yml`，本地可直接运行：

```bash
cp .env.example .env
docker compose up --build
```

## PWA 缓存边界

前端使用 `vite-plugin-pwa` 和 Workbox 自定义 Service Worker。Service Worker 只预缓存应用外壳、哈希 JS/CSS、字体、Manifest 和应用图标：

- 任意跨域请求始终使用 NetworkOnly，包括 Railway API 和 R2 预签名上传 URL。
- 同源 `/api/**` 和 `/actuator/**` 始终使用 NetworkOnly。
- 不缓存登录、刷新令牌、用户、Tenant、商品、库存、订单、报表或认证后的 API 响应。
- 不启用 Background Sync、推送或写请求队列。

新版本保持 waiting，只有用户确认后才激活并刷新。退出账号时清理内存用户状态，但不删除不含业务数据的静态应用缓存。

## Cloudflare Pages

继续使用现有 Pages 项目：

- Root directory：`frontend`
- Build command：`npm run build`
- Output directory：`dist`
- `NODE_VERSION=24`
- Preview 和 Production 都配置 `VITE_API_BASE_URL=https://API_DOMAIN/api/v1`

`public/_headers` 要求 `sw.js` 不使用浏览器缓存、Manifest 每次重新验证，并只为哈希 `/assets/*` 设置 immutable。不要创建缓存 Railway API 或 R2 私有 URL 的 Cloudflare Cache Rule。

### Preview

1. 使用 PR 分支的稳定 Pages 别名，不使用每次构建变化的 hash URL。
2. 将该精确 origin 临时加入 Railway `CORS_ALLOWED_ORIGINS`；凭据请求不能使用通配符。
3. 建议通过 Cloudflare Access 限制 Preview，并只使用测试 Tenant 和账号。
4. 检查 Manifest、安装、standalone、离线提示、更新提示和 CacheStorage 白名单。
5. 验收后卸载手机上的 Preview PWA，并移除临时 CORS origin。

### Production

CI、Preview 和真机测试全部通过后才能合并生产分支。生产部署后检查：

- `/sw.js`、`/manifest.webmanifest`、图标和 SPA 深链接可访问。
- 已安装旧版本只提示更新，不自动刷新正在填写的表单。
- 登录、会话刷新、查询、单次测试写入和退出均正常。
- CacheStorage 中不存在 API、Tenant 或 R2 URL。

## Railway Serverless

仓库已使用 `${PORT:8080}`，匿名开放 `/actuator/health/**`，生产 Hikari 配置为 `minimum-idle: 0`、5 分钟 idle timeout 和最多 3 条连接。Railway UI 需要手工确认：

1. Healthcheck Path 为 `/actuator/health`，服务使用单副本。
2. 设置 `SPRING_PROFILES_ACTIVE=prod` 以及上方列出的连接池变量。
3. 确认没有 UptimeRobot、Better Uptime、自建 cron、外部健康探测或遥测定期访问服务。
4. Preview 验收后，在 `Settings → Deploy → Serverless` 开启 Serverless。
5. 空闲超过 10 分钟后确认部署进入 `SLEEPING`，再执行一次冷启动测试。

应用启动时先以 GET 请求 `/actuator/health` 唤醒服务，再单次刷新会话。只有 GET/HEAD 会针对网络错误、超时、502、503、504 自动重试两次，退避为 750ms 和 1500ms；POST、PUT、PATCH、DELETE 永不因冷启动自动重试。写请求若在结果确认前断网，应先刷新对应列表确认结果，不能直接重复提交。

若服务无法休眠，先检查实际 active profile、Neon 活跃连接、外部探活和 Railway Metrics。可随时关闭 Serverless 回到常驻模式，不需要回滚数据库。

## 数据库升级

部署新版本前备份 PostgreSQL。V8 会删除旧版通用销售、文件导入和外部交易结构，并要求旧核心交易与销量批次已经有展会归属。若环境仅有开发数据，建议在升级前重置本地数据库；不要通过伪造展会绕过迁移检查。

生产升级流程：

1. 备份并验证恢复点。
2. 在同版本副本上执行全部 Flyway 迁移。
3. 检查迁移日志和约束验证。
4. 部署后端并确认 `/actuator/health`。
5. 部署使用匹配 API 地址构建的前端。
6. 执行冒烟测试。

删除历史列的收缩迁移必须先验证数据库备份可恢复，并在恢复副本上演练升级。V13 会删除商品售价、成本、商品币种、重复公共资料及旧文件归属列；旧应用不能连接 V13 数据库，回退必须恢复 V13 前备份。

## 健康检查与冒烟测试

- `GET /actuator/health` 返回健康。
- Android 可以通过应用内按钮安装，iPhone 可以按引导添加到主屏幕。
- standalone 模式安全区、离线提示和用户确认更新正常。
- Service Worker CacheStorage 中只有静态应用资产。
- Railway 冷启动时只重试 GET/HEAD，写请求在 Network 面板中始终只有一次。
- 管理员与普通用户能够登录和刷新会话。
- 创建展会后，可录入一批金额并逐笔编辑、删除。
- 同一展会可登记商品销量，库存不足时整批不变。
- 金额报表与商品数量报表分别正确聚合。
- 一次创建包含多个规格的商品系列，所有规格共享图片并保持独立库存。
- 商品图片可上传、读取、替换和删除。
- 普通用户无法访问其他 Tenant 数据或管理员端点。
- A 账号退出并登录 B 后，浏览器返回、多标签页和 bfcache 恢复均不能显示 A 的数据。

## 安全与运维

- API 与前端只通过 HTTPS 暴露。
- 数据库和对象存储凭据使用平台 Secret 管理。
- 定期备份 PostgreSQL 与商品图片 Bucket，并进行恢复演练。
- 监控健康检查、5xx、登录限流、Refresh Token 重放、库存不足和迁移失败。
- 日志通过 `traceId` 关联请求，不记录令牌、Cookie 或密钥。

商品图片的 Bucket、CORS 和权限设置见 [商品图片存储](r2-storage.md)。
