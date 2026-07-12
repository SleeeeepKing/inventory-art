# 生产部署：Railway + Neon + Cloudflare

## 目标拓扑

- Vue 静态站点：Cloudflare Pages（建议自定义域名 `app.example.com`）
- Spring Boot API：Railway 欧洲区域（建议 `api.example.com`）
- PostgreSQL：Neon Frankfurt
- 私有对象：Cloudflare R2

最终域名必须通过环境变量和平台设置提供，不能在代码中硬编码。部署顺序为 Neon → R2 → Railway → Pages → 自定义域名/CORS → 验证。

## 发布前验证

在干净环境执行：

```bash
cd backend
mvn -B test
mvn -B -DskipTests package

cd ../frontend
npm ci
npm run lint
npm run typecheck
npm run test:run
npm run build

cd ..
docker compose config
```

Testcontainers 和 Compose 都要求 Docker daemon 已启动。不要通过 `-DskipTests` 完成测试步骤；上面的跳过仅用于已经通过测试后的独立打包校验。

## 1. Neon PostgreSQL

1. 在 Neon 创建 Project，Region 选择 Europe / AWS Frankfurt（控制台实际名称可能包含 `eu-central-1`）。
2. 创建专用生产数据库和应用角色，不复用个人管理员账号。
3. 在 Connect 页面复制连接信息。当前应用同时由 Flyway 和 Hikari 使用同一 URL，优先使用 direct hostname，并把 Hikari 最大池保持为 3；这避免 transaction pool 对 migration/session 行为的限制。
4. 将 PostgreSQL URI 转为 JDBC URL，至少强制 TLS：

   ```text
   DATABASE_URL=jdbc:postgresql://<endpoint>.<region>.aws.neon.tech/<database>?sslmode=require
   DATABASE_USERNAME=<application-role>
   DATABASE_PASSWORD=<secret>
   DATABASE_POOL_SIZE=3
   DATABASE_MIN_IDLE=0
   ```

5. 不设置 `spring.jpa.hibernate.ddl-auto=update`。应用启动时 Flyway 自动执行 migration，Hibernate 只验证结构。
6. 在预生产分支先运行 migration；创建 Neon branch 或恢复点后再升级生产。

若以后使用 Neon pooled hostname（包含 `-pooler`），先验证 Flyway 和所用 PostgreSQL/JPA 功能兼容；`pg_dump`、恢复、长事务和 migration 建议仍使用 direct connection。参考 [Neon connection pooling](https://neon.com/docs/connect/connection-pooling)。

### 数据库备份与恢复

- 定期确认 Neon 的恢复/branch 功能满足保留要求，并额外保存可移植的 `pg_dump`。
- 恢复演练使用 direct connection，验证 Flyway history、对象 metadata 与 R2 对象的一致性。
- 应用回滚优先部署上一版本；schema 通过新的前向修复 migration 修正，不能修改已经执行的 Flyway 文件。

## 2. Cloudflare R2

1. 创建私有 Bucket；不启用公共开发 URL或匿名列举。
2. 创建只允许该 Bucket Object Read & Write 的 API Token，保存 Access Key ID、Secret Access Key 和 S3 endpoint。
3. 按实际 Pages 域名配置 PUT/GET/HEAD 的 CORS。
4. 将下列变量提供给 Railway：

   ```text
   STORAGE_PROVIDER=r2
   R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
   # 仅当浏览器访问地址与容器内部 endpoint 不同时设置：
   R2_PUBLIC_ENDPOINT=https://<PUBLIC_OBJECT_ENDPOINT>
   R2_REGION=auto
   R2_ACCESS_KEY_ID=<secret>
   R2_SECRET_ACCESS_KEY=<secret>
   R2_BUCKET_PRIVATE=<bucket-name>
   R2_PRESIGNED_URL_EXPIRATION_SECONDS=900
   ```

完整 Bucket、CORS、key 命名、预签名验证与轮换步骤见 [r2-storage.md](r2-storage.md)。

## 3. Railway 后端

### 创建服务

1. 在 Railway 创建 Project，连接 GitHub 仓库并新建 backend service。
2. 将服务的 **Root Directory** 设置为 `/backend`。这很重要：`backend/Dockerfile` 的 `COPY pom.xml`/`COPY src` 以 `backend` 为构建上下文。
3. Builder 选择 Dockerfile。Root Directory 设置后默认找到 `/backend/Dockerfile`；不要把根 `docker-compose.yml` 当作生产运行方式。
4. Region 选择靠近 Neon Frankfurt 的欧洲区域。
5. 设置 Healthcheck Path 为 `/actuator/health`，超时建议 300 秒；Restart Policy 使用 `ON_FAILURE`。
6. 生成 Railway HTTPS domain，之后可添加 `api.example.com` 自定义域名。

Railway 会把 `PORT` 注入容器；Spring 配置读取该变量。Dockerfile 使用 Java 21 多阶段构建和非 root 运行用户。官方配置参考：[Railway Dockerfiles](https://docs.railway.com/builds/dockerfiles) 与 [Healthchecks](https://docs.railway.com/deployments/healthchecks)。

### Railway Variables

以下值必须放在 Production environment 的 Variables/Secrets 中：

```text
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=jdbc:postgresql://.../<db>?sslmode=require
DATABASE_USERNAME=...
DATABASE_PASSWORD=...
DATABASE_POOL_SIZE=3
DATABASE_MIN_IDLE=0

JWT_SECRET=<at-least-32-bytes-high-entropy-random-secret>
JWT_ACCESS_TOKEN_MINUTES=15
JWT_REFRESH_TOKEN_DAYS=30
COOKIE_SECURE=true
CORS_ALLOWED_ORIGINS=https://app.example.com

STORAGE_PROVIDER=r2
R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
# 通常留空并沿用 R2_ENDPOINT；仅在内外 endpoint 不同时设置
R2_PUBLIC_ENDPOINT=
R2_REGION=auto
R2_ACCESS_KEY_ID=...
R2_SECRET_ACCESS_KEY=...
R2_BUCKET_PRIVATE=...
R2_PRESIGNED_URL_EXPIRATION_SECONDS=900

IMPORT_MAX_FILE_SIZE=20MB
IMPORT_MAX_BYTES=20971520
IMPORT_BATCH_SIZE=200
IMPORT_MAX_ROWS=20000
IMPORT_XLS_MAX_BYTES=5242880
APP_SEED_ENABLED=false
JAVA_TOOL_OPTIONS=-Xms48m -Xmx192m -Xss512k -XX:MaxMetaspaceSize=144m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=16m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError
```

Railway 0.5 GB 实例必须保留这些保守上限：JVM 堆最大 192 MB、Metaspace 最大 144 MB、Serial GC、Hikari 最多 3 条连接、Tomcat 最多 16 个工作线程，以及单工作线程/4 个排队任务的有界异步执行器。144 MB 的 Metaspace 上限可容纳 Spring Boot、Hibernate、Flyway 和 springdoc 初始化后的类元数据，同时仍为 512 MB 容器预留线程栈、代码缓存、直接内存和本地库空间。导入每批最多 200 行且单文件最多 20,000 个数据行；旧版 XLS 额外通过 `IMPORT_XLS_MAX_BYTES=5242880` 限制为 5 MB，避免 XLSX/CSV、Hibernate 批处理和并发请求同时把内存推到容器上限。不要仅设置 `MaxRAMPercentage`，它没有为 Metaspace、线程栈、直接内存和本地库明确预留空间。

首次创建管理员时，通过 Railway Secret 临时设置：

```text
ADMIN_BOOTSTRAP_USERNAME=<non-default-admin-name>
ADMIN_BOOTSTRAP_PASSWORD=<unique-strong-password>
```

确认管理员已安全创建并能登录后，立即轮换密码并移除初始化变量。生产不能启用 `dev` profile，不能出现 `admin/Admin123!` 或 `user1/User123!` 演示凭据。

`CORS_ALLOWED_ORIGINS` 使用逗号分隔的完整 Origin，不带路径、不使用 `*`。若先使用 `*.pages.dev` 临时地址，应添加部署中实际使用的精确 Origin；不要为了预览环境放开所有子域。

### 首次启动检查

查看 Railway stdout：

- Flyway 成功迁移，Hibernate schema validation 通过；
- 数据库错误不打印密码；
- 服务绑定 Railway `PORT`；
- `/actuator/health` 返回 HTTP 200 和 `UP`；
- 容器没有依赖本地持久磁盘保存图片或导入文件。

然后检查：

```bash
curl --fail https://api.example.com/actuator/health
```

Swagger 默认路径为 `/swagger-ui.html`。如生产不需要公开 Swagger，应在反向代理/安全配置中限制，而不是暴露 Entity 或敏感示例。

## 4. Cloudflare Pages 前端

1. Workers & Pages → Create application → Pages → 连接 GitHub 仓库。
2. 设置：

   | 设置 | 值 |
   | --- | --- |
   | Root directory | `frontend` |
   | Framework preset | Vue / Vite |
   | Build command | `npm run build` |
   | Build output directory | `dist` |
   | Node version | `24` |

3. Production environment variable：

   ```text
   VITE_API_BASE_URL=https://api.example.com/api/v1
   ```

4. 部署。`frontend/public/_redirects` 在构建后进入 `dist`，提供 Vue Router history fallback：

   ```text
   /* /index.html 200
   ```

5. 添加 `app.example.com` 自定义域名，并把最终 HTTPS Origin 同步到 Railway `CORS_ALLOWED_ORIGINS` 和 R2 CORS。

`VITE_API_BASE_URL` 在构建时写入静态资源；修改变量后必须触发新 Pages deployment。Cloudflare 当前 Vue/Vite 推荐 `npm run build` 和 `dist`，参见 [Pages build configuration](https://developers.cloudflare.com/pages/configuration/build-configuration/)。依赖由 lockfile 固定；本地和 CI 使用 `npm ci` 验证同一依赖图。

### Preview 部署

Cloudflare Pages 的预览 URL 会变化，而凭据 CORS 和 Refresh Cookie 需要精确 Origin。安全默认是预览前端只连接专用预览 API，并把该次明确 URL加入预览 API allowlist；不要让任意 Pages preview origin 访问生产带凭据 API。

## 5. 域名与跨域闭环

建议使用：

```text
https://app.example.com       Cloudflare Pages
https://api.example.com       Railway backend
```

配置完成后同时满足：

- `VITE_API_BASE_URL=https://api.example.com/api/v1`
- `CORS_ALLOWED_ORIGINS=https://app.example.com`
- `COOKIE_SECURE=true`
- R2 CORS `AllowedOrigins` 包含 `https://app.example.com`
- API 和 Pages 均有有效 HTTPS 证书

Refresh/Logout 是 Cookie 请求，后端还会检查 Origin；仅配置浏览器 CORS 但忘记后端 allowlist 会导致刷新失败。

## 6. 端到端验收

按以下顺序验证生产或 staging：

1. `/actuator/health` 为 UP；Flyway schema 与应用版本一致。
2. 使用生产管理员登录；浏览器中 Access Token 不出现在 Local Storage，Refresh Cookie 为 HttpOnly/Secure。
3. 创建两个测试 Tenant/User，确认用户 A 猜测用户 B 商品/订单/文件 ID 时得到 404。
4. 新用户首次登录为英文；保存 `zh-CN`、退出并重新登录后恢复中文，再验证 `fr-FR`。
5. 创建商品、获取预签名 PUT、从浏览器上传图片并完成 HEAD 确认；直接匿名列举 Bucket 失败。
6. 调整库存并检查流水；再创建、确认、取消和退款订单，确认这些订单操作都不改变库存。
7. 上传合成 SumUp 文件：上传、确认和撤销都不改库存；重复文件被拒绝；错误 CSV 受 Tenant 保护。
8. 报表对同一订单和关联 SumUp 交易只计算一次，不同币种分别显示。
9. ADMIN 跨 Tenant 查询生成 AuditLog。

## 监控与运维

- Railway：关注内存、重启、5xx、数据库连接失败、Flyway 失败和 healthcheck。
- Neon：关注连接数、存储、慢查询和分支/恢复状态；0.5 GB Railway 实例的 Hikari 最大连接为 3。
- R2：关注 4xx/5xx、未确认上传、对象与 metadata 不一致以及 Token 使用范围。
- 应用：按 traceId 关联请求；监控登录限流、Refresh 重放、导入 FAILED/长时间 IMPORTING、撤销冲突和库存一致性告警。
- 日志和 AuditLog 不得包含密码、JWT、Refresh Cookie、R2 Secret、完整银行卡号或导入原始敏感行。

## 发布与回滚

1. GitHub Actions 全部通过后再合并发布分支。
2. 先在 Neon branch + Railway staging + Pages preview 执行 migration 和端到端冒烟。
3. 生产发布后观察 health、错误率和 migration 日志，再发布前端。
4. 应用失败时部署上一个兼容镜像/commit；不要执行破坏性数据库回滚。
5. schema 问题使用新的 Flyway 前向修复；数据问题通过受审计的 Service/修复任务处理。
6. R2 凭据泄露时先创建新 Token、更新 Railway、验证，再撤销旧 Token；JWT Secret 轮换会使现有会话失效，应安排维护窗口。
