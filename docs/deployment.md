# 部署

系统由 Vue 静态前端、Spring Boot 后端、PostgreSQL 和可选的 S3 兼容商品图片存储组成。后端启动时由 Flyway 执行迁移，Hibernate 只验证结构。

## 生产环境变量

至少配置：

```dotenv
DATABASE_URL=jdbc:postgresql://host:5432/inventory_art
DATABASE_USERNAME=inventory
DATABASE_PASSWORD=strong-password
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

前端的 `VITE_API_BASE_URL` 是构建时变量。后端还支持 `DATABASE_POOL_SIZE`、`JWT_ACCESS_TOKEN_MINUTES`、`JWT_REFRESH_TOKEN_DAYS` 和 `COOKIE_SAME_SITE` 等可选参数。

## 构建

```bash
cd backend && ./mvnw clean package
npm --prefix frontend ci
npm --prefix frontend run build
```

仓库包含前后端 Dockerfile 和 `docker-compose.yml`，本地可直接运行：

```bash
cp .env.example .env
docker compose up --build
```

## 数据库升级

部署新版本前备份 PostgreSQL。V8 会删除旧版通用销售、文件导入和外部交易结构，并要求旧核心交易与销量批次已经有展会归属。若环境仅有开发数据，建议在升级前重置本地数据库；不要通过伪造展会绕过迁移检查。

生产升级流程：

1. 备份并验证恢复点。
2. 在同版本副本上执行全部 Flyway 迁移。
3. 检查迁移日志和约束验证。
4. 部署后端并确认 `/actuator/health`。
5. 部署使用匹配 API 地址构建的前端。
6. 执行冒烟测试。

## 健康检查与冒烟测试

- `GET /actuator/health` 返回健康。
- 管理员与普通用户能够登录和刷新会话。
- 创建展会后，可录入一批金额并逐笔编辑、删除。
- 同一展会可登记商品销量，库存不足时整批不变。
- 金额报表与商品数量报表分别正确聚合。
- 商品图片可上传、读取、替换和删除。
- 普通用户无法访问其他 Tenant 数据或管理员端点。

## 安全与运维

- API 与前端只通过 HTTPS 暴露。
- 数据库和对象存储凭据使用平台 Secret 管理。
- 定期备份 PostgreSQL 与商品图片 Bucket，并进行恢复演练。
- 监控健康检查、5xx、登录限流、Refresh Token 重放、库存不足和迁移失败。
- 日志通过 `traceId` 关联请求，不记录令牌、Cookie 或密钥。

商品图片的 Bucket、CORS 和权限设置见 [商品图片存储](r2-storage.md)。
