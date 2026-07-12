# Inventory Art

Inventory Art 是一个面向个人创作者、小型展会摊主和周边商品销售者的多用户库存与销售管理系统。系统采用 Tenant（工作空间）隔离数据，支持手工订单、库存流水、SumUp 文件导入、销售报表、管理员全局管理，以及英文、简体中文、法语三种界面语言。

## 功能概览

- 用户登录、短期 JWT Access Token、可轮换 Refresh Token、退出和个人设置。
- USER 只能访问自己 Tenant 的商品、库存、订单、文件、导入和报表；ADMIN 使用独立接口查看全局数据。
- 商品、低库存阈值、私有图片和预签名上传。
- 只通过库存服务写入的库存调整、订单扣减、取消/退款恢复和不可变库存流水。
- 草稿、确认、完成、取消和退款订单流程；后端统一重算金额。
- CSV、XLS、XLSX SumUp 导入向导：检测、列映射、预览、商品映射、确认、错误导出和撤销。
- 集中式报表数据源去重，按币种分别统计，不猜测汇率。
- English (`en`)、简体中文 (`zh-CN`) 和 Français (`fr-FR`) 完整 i18n。未登录及用户首次登录默认英文；登录后的语言偏好保存到用户账号。
- 移动端优先的响应式界面：手机使用抽屉导航、全宽操作区、触控尺寸控件和可横向滚动的数据表。
- PostgreSQL/Flyway、MinIO/R2、Docker Compose、GitHub Actions，以及 Railway + Neon + Cloudflare Pages + Cloudflare R2 部署方案。

## 技术栈与结构

- 后端：Java 21、Spring Boot 3.5、Spring Security、JPA、Flyway、PostgreSQL、Apache Commons CSV、Apache POI、AWS SDK v2（仅作为 S3-compatible 客户端）。
- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus、ECharts、Vue I18n、Vitest。
- 测试：JUnit 5、MockMvc、Testcontainers PostgreSQL、Vitest、Vue Test Utils。

```text
.
├── backend/                 Spring Boot 模块化单体、Flyway 和后端测试
├── frontend/                Vue SPA、三语言 locale 和前端测试
├── docs/                    架构、安全、数据库、导入和部署文档
├── .github/workflows/       后端与前端 CI
├── docker-compose.yml       PostgreSQL、MinIO、后端、前端
└── .env.example             本地环境变量模板（不含真实密钥）
```

详细设计见 [架构](docs/architecture.md)、[数据库](docs/database-schema.md)、[安全与租户隔离](docs/security-and-tenancy.md)、[账号初始化](docs/account-bootstrap.md)、[SumUp 导入](docs/sumup-import.md)、[部署](docs/deployment.md) 和 [R2 存储](docs/r2-storage.md)。

## 本地快速启动

### 前置条件

- Docker Desktop、OrbStack 或其他可用的 Docker Engine，并且 Docker daemon 已启动。
- 本机开发可选：JDK 21 + Maven 3.9，以及 Node.js 24 + npm。
- 建议至少预留 4 GB 内存给 Compose 服务。

> Testcontainers 集成测试和 `docker compose up` 都依赖可用的 Docker daemon。仅安装 Docker CLI 不够。

### 全部使用 Docker Compose

```bash
cp .env.example .env
docker compose up --build
```

Compose 默认把后端限制在 512 MB，以便本地尽早暴露与 Railway 小实例相同的内存问题；需要调试分析器时可临时通过 `BACKEND_MEMORY_LIMIT` 覆盖。

服务启动后：

- 前端：<http://localhost:4173>
- 后端 API：<http://localhost:8080/api/v1>
- Swagger UI：<http://localhost:8080/swagger-ui.html>
- 健康检查：<http://localhost:8080/actuator/health>
- MinIO Console：<http://localhost:9001>

停止服务：

```bash
docker compose down
```

需要同时删除本地 PostgreSQL 和 MinIO 数据时才使用：

```bash
docker compose down -v
```

### 使用本机开发服务器

先启动依赖：

```bash
cp .env.example .env
docker compose up -d postgres minio minio-init
```

启动后端：

```bash
cd backend
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

启动前端：

```bash
cd frontend
npm ci
npm run dev
```

Vite 默认地址为 <http://localhost:5173>。宿主机直跑后端默认使用 `local` 存储；若要改用 MinIO，同时设置 `STORAGE_PROVIDER=minio`、`R2_ENDPOINT=http://localhost:9000`、`R2_REGION=us-east-1` 以及本地 MinIO 的 access key、secret 和 bucket。Compose 内后端使用可从容器和宿主浏览器解析的 `http://minio.localhost:9000`。

## 开发演示账号

`dev` profile 会启用仅限本地的演示数据。三个账号初始 UI 语言均为英文。

| 角色 | 用户名 | 密码 | Tenant |
| --- | --- | --- | --- |
| ADMIN | `admin` | `Admin123!` | 无（全局管理员） |
| USER | `user1` | `User123!` | 独立 Tenant 1 |
| USER | `user2` | `User123!` | 独立 Tenant 2 |

这些密码只能用于本地开发。生产环境不得启用 `dev` profile 或 `APP_SEED_ENABLED=true`，也不得复用任何演示密码。

## 环境变量

复制 `.env.example` 作为本地起点。生产密钥必须放在部署平台的 Secret/Variables 中，不能提交到 Git。

| 变量 | 用途 | 本地默认/示例 |
| --- | --- | --- |
| `PORT` | 后端监听端口（Railway 自动注入） | `8080` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `dev`（仅本地） |
| `DATABASE_URL` | JDBC PostgreSQL URL | `jdbc:postgresql://localhost:5432/inventory_art` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | 数据库凭据 | `inventory` / 本地密码 |
| `DATABASE_POOL_SIZE` | Hikari 最大连接数 | `3` |
| `JWT_SECRET` | JWT HMAC 密钥，生产使用高熵随机值 | 无安全默认值 |
| `JWT_ACCESS_TOKEN_MINUTES` | Access Token 有效期 | `15` |
| `JWT_REFRESH_TOKEN_DAYS` | Refresh Token 有效期 | `30` |
| `CORS_ALLOWED_ORIGINS` | 逗号分隔的精确前端 Origin | 本地 Vite/Compose 地址 |
| `COOKIE_SECURE` | Refresh Cookie 是否只允许 HTTPS | 本地 `false`；生产 `true` |
| `STORAGE_PROVIDER` | `local`、`minio` 或 `r2` | Compose 为 `minio` |
| `LOCAL_STORAGE_PATH` | 仅本地文件存储根目录 | `./storage-data` |
| `R2_ENDPOINT` | R2/MinIO S3 endpoint | Compose 为 `http://minio.localhost:9000` |
| `R2_PUBLIC_ENDPOINT` | 可选的浏览器预签名 URL endpoint；未设置时沿用 `R2_ENDPOINT` | Compose 为 `http://minio.localhost:9000` |
| `R2_REGION` | S3-compatible region | R2 为 `auto` |
| `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | 私有 Bucket API 凭据 | MinIO 本地凭据 |
| `R2_BUCKET_PRIVATE` | 私有对象 Bucket | `inventory-art` |
| `R2_PRESIGNED_URL_EXPIRATION_SECONDS` | 预签名 URL 有效期 | `900` |
| `IMPORT_MAX_FILE_SIZE` | Spring multipart 请求上限 | `20MB` |
| `IMPORT_MAX_BYTES` | 业务层文件大小上限（字节） | `20971520` |
| `IMPORT_BATCH_SIZE` | 导入批处理行数 | `200` |
| `IMPORT_MAX_ROWS` | 单个导入允许的数据行上限 | `20000` |
| `IMPORT_XLS_MAX_BYTES` | 旧版 XLS 的内存安全上限 | `5242880`（5 MB） |
| `APP_SEED_ENABLED` | 启用演示数据 | 生产必须为 `false` |
| `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` | 生产首次管理员安全初始化 | 生产 Secret；初始化后轮换/移除 |
| `VITE_API_BASE_URL` | 前端构建时写入的 API 根地址 | `http://localhost:8080/api/v1` |

`VITE_*` 是构建时变量；变更生产 API 地址后必须重新构建前端。完整生产设置见 [部署文档](docs/deployment.md)。

## 测试与构建

后端（关键集成测试使用 PostgreSQL Testcontainers，因此先启动 Docker daemon）：

```bash
cd backend
mvn -B test
mvn -B -DskipTests package
```

前端：

```bash
cd frontend
npm ci
npm run lint
npm run typecheck
npm run test:run
npm run build
```

容器配置和完整启动验证：

```bash
docker compose config
docker compose up --build
```

GitHub Actions 在 Java 21 和 Node 24 上执行同样的测试与构建。不要通过跳过失败测试完成合并。

## API 约定

- API 根路径为 `/api/v1`；Swagger 只公开 DTO，不直接公开 JPA Entity。
- 分页响应统一为 `{items,page,size,totalElements,totalPages,sort}`。
- 错误响应包含 `timestamp`、HTTP `status`、稳定 `code`、`message`、`path`、可选 `fieldErrors` 和 `traceId`。
- Access Token 由前端保存在内存；Refresh Token 只通过 HttpOnly Cookie 发送。
- 普通业务请求不接受可用于越权的 `tenantId`；后端从已认证用户解析 Tenant。

## 语言行为

- 未认证页面启动时使用英文；用户可在登录页临时切换语言，该选择不写入服务端。
- 新用户 `preferred_locale` 默认 `en`，因此首次登录使用英文，且不会按浏览器语言自动切换。
- 登录或刷新会话后，以服务端返回的 `preferredLocale` 为准；个人设置保存后立即切换，并在其他设备登录时恢复。
- 可选值只允许 `en`、`zh-CN`、`fr-FR`。英文是缺失翻译 fallback。
- 用户界面语言与 Tenant 的 `locale` 不同：前者控制文案和 `Intl` 格式，Tenant 的时区和默认币种仍控制业务日期与金额。

## SumUp 文件导入

1. 上传 CSV/XLS/XLSX；后端验证格式和大小、计算 SHA-256，并保存私有原文件。
2. 分析编码、分隔符、英/法表头、报告类型和数据粒度；上传和分析阶段不修改订单或库存。
3. 检查并修正列映射，预览标准化数据和错误。
4. 映射外部商品；匹配顺序为已保存映射、Reference/SKU、规范化名称、手动映射、未分配。
5. 查看新增、更新、重复、错误和库存影响，携带当前分析版本明确确认。
6. 后端在行数上限保护的事务中执行幂等导入；完成后可查看错误 CSV，满足条件的批次可以通过反向流水撤销。

文件 checksum 防止重复上传；交易优先按外部 ID 去重，无稳定 ID 时使用确定性 fingerprint。交易级、订单级、商品汇总和会计汇总的处理方式不同，详见 [SumUp 导入规范](docs/sumup-import.md)。

## 生产部署摘要

1. 在 Neon Frankfurt 创建 PostgreSQL 数据库，使用强制 TLS 的 JDBC URL，并限制初始连接池为 3。
2. 在 Cloudflare R2 创建私有 Bucket 和仅限该 Bucket 的读写凭据，配置浏览器 PUT/GET 所需 CORS。
3. 在 Railway 从本仓库创建后端服务，使用 `backend/Dockerfile`、配置环境变量，并将健康检查设为 `/actuator/health`。
4. 在 Cloudflare Pages 连接同一 GitHub 仓库；Root directory 为 `frontend`，命令 `npm run build`，输出 `dist`，设置 `VITE_API_BASE_URL`。
5. 配置 `app.example.com` / `api.example.com` 后，把精确 HTTPS 前端 Origin 写入 `CORS_ALLOWED_ORIGINS`，并设置 `COOKIE_SECURE=true`。

逐项步骤和回滚检查见 [部署文档](docs/deployment.md) 与 [R2 配置](docs/r2-storage.md)。

## 已知限制

- MVP 不提供公开注册、订阅计费、套餐限制、自助邀请流程或 Tenant 内细粒度角色；ADMIN 可把多个 USER 分配到同一 Tenant。
- 不实现汇率换算；不同币种分组展示，不能直接相加。
- 仅支持用户上传 SumUp 导出文件；不调用 SumUp API，不实现 Checkout 或刷卡机控制。
- 商品汇总与会计汇总默认只用于对账；商品汇总调整库存需要额外确认。
- 多租户隔离由应用认证上下文、Tenant-aware 查询、数据库组合约束和测试共同保证；当前不启用 PostgreSQL RLS。
- 浏览器上传总上限默认 20 MB，单文件最多 20,000 行；XLSX 使用 SAX 流式读取，旧版 XLS 因格式限制默认最多 5 MB。
- Railway 512 MB 部署使用 192 MB heap、16 个请求线程、有界异步队列、最多 3 个数据库连接和 200 行批处理；不要在生产环境移除这些限制。
- R2 与 MinIO 提供的是 S3-compatible 存储；系统不部署或依赖任何 AWS 服务。
- 当前为模块化单体；导入状态持久化在数据库中并支持幂等重试，不拆分为独立微服务或任务 Worker。
- 正式导入为保证业务原子性会在一个数据库事务中确认，当前用 20,000 行上限控制事务规模；超大文件应先拆分。
- TypeScript 当前固定为 5.9，因为现有 Vue/Vite 类型检查链与 TypeScript 7 尚不兼容；升级前必须先让 `vue-tsc`、Vitest 和生产构建在 CI 中全部通过。
