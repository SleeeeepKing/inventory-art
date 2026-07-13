# Inventory Art

Inventory Art 是一个面向展会参展人员的多租户销售与库存统计系统。系统用于在展会结束后录入纸笔记录，只保存能够从原始记录确认的两类事实：

- 交易记录：展会、销售小时、交易金额。
- 商品销量：展会、商品、售出数量。

系统不保存客户身份、支付方式、支付状态、退款、销售渠道或订单商品关联，也不提供第三方交易文件导入。

## 功能

- 快速交易录入：选择展会和小时后，连续录入最多 100 个正数金额；保存后可继续录入下一批。
- 交易维护：按展会、日期和订单号检索，并可逐笔编辑或删除。
- 商品与库存：维护商品资料、库存增减和精确修正；按展会批量登记商品销量，并可修改或永久撤销误录批次。
- 展会支出：使用 Tenant 共享的自定义类别记录、修改和作废展会支出。
- 报表：销售额、支出、展会结余、分类支出、日/小时趋势，以及最终有效的商品销量和售出批次数。
- 多租户与三语言界面：Tenant 数据隔离，支持英文、简体中文和法语。
- 商品图片：本地文件系统或 S3 兼容对象存储。

商品标准售价和成本价仅作为商品目录资料，不用于推算实际成交收入。

## 技术栈

- 后端：Java 21、Spring Boot、Spring Security、Spring Data JPA、Flyway、PostgreSQL。
- 前端：Vue 3、TypeScript、Vite、Pinia、Element Plus、ECharts。
- 存储：本地文件系统或 Cloudflare R2/MinIO 等 S3 兼容存储。
- 测试：JUnit、Testcontainers、Vitest。

## 本地启动

复制环境变量并启动完整环境：

```bash
cp .env.example .env
docker compose up --build
```

默认地址：

- 前端：`http://localhost:4173`
- 后端：`http://localhost:8080`
- OpenAPI：`http://localhost:8080/swagger-ui.html`
- MinIO 控制台：`http://localhost:9001`

也可以仅启动依赖后分别运行前后端：

```bash
docker compose up -d postgres minio minio-init
cd backend && ./mvnw spring-boot:run
npm --prefix frontend install
npm --prefix frontend run dev
```

### IDEA 调试生产数据库和 R2

需要从本地前端和 IDEA 后端复现生产数据问题时，后端同时激活 `prod` 和
`local-prod-debug` profile：

```dotenv
SPRING_PROFILES_ACTIVE=prod,local-prod-debug
DATABASE_URL=jdbc:postgresql://PRODUCTION_HOST:5432/PRODUCTION_DATABASE
DATABASE_USERNAME=...
DATABASE_PASSWORD=...
JWT_SECRET=...
CORS_ALLOWED_ORIGINS=http://localhost:5173
R2_ENDPOINT=https://ACCOUNT_ID.r2.cloudflarestorage.com
R2_PUBLIC_ENDPOINT=https://ACCOUNT_ID.r2.cloudflarestorage.com
R2_REGION=auto
R2_ACCESS_KEY_ID=...
R2_SECRET_ACCESS_KEY=...
R2_BUCKET_PRIVATE=PRODUCTION_BUCKET_NAME
```

前端使用 `VITE_API_BASE_URL=http://localhost:8080/api/v1` 后运行
`npm --prefix frontend run dev`。`local-prod-debug` 只允许精确的 `localhost` 或
`127.0.0.1` HTTP origin，并仅放宽本地 Cookie；JWT、R2 完整配置、禁用 seed 等生产校验仍然生效。
R2 参数应逐项复制生产环境的实际值，尤其要确认 `R2_BUCKET_PRIVATE` 与生产 Bucket 名称完全一致。
不要把生产凭据写入仓库。该模式会真实读写生产数据库和 Bucket，后端启动时也可能执行 Flyway 迁移。

首次管理员账号的创建方式见 [账号初始化](docs/account-bootstrap.md)。

## 核心配置

| 变量                                                     | 用途                                       |
| -------------------------------------------------------- | ------------------------------------------ |
| `DATABASE_URL`、`DATABASE_USERNAME`、`DATABASE_PASSWORD` | PostgreSQL 连接                            |
| `JWT_SECRET`                                             | JWT 签名密钥，生产环境必须使用高强度随机值 |
| `CORS_ALLOWED_ORIGINS`                                   | 允许访问 API 的前端来源                    |
| `COOKIE_SECURE`                                          | 生产 HTTPS 环境设为 `true`                 |
| `STORAGE_PROVIDER`                                       | `local`、`r2` 或 `minio`                   |
| `LOCAL_STORAGE_PATH`                                     | 本地商品图片目录                           |
| `R2_ENDPOINT`、`R2_PUBLIC_ENDPOINT`                      | S3 兼容 API 与浏览器可访问端点             |
| `R2_ACCESS_KEY_ID`、`R2_SECRET_ACCESS_KEY`               | 对象存储凭据                               |
| `R2_BUCKET_PRIVATE`                                      | 商品图片私有 Bucket                        |
| `R2_PRESIGNED_URL_EXPIRATION_SECONDS`                    | 预签名上传 URL 有效期                      |
| `VITE_API_BASE_URL`                                      | 前端构建时 API 地址                        |

完整部署说明见 [部署文档](docs/deployment.md)。

## 数据模型约束

- 每笔交易和每个商品销量批次都必须属于一个展会。
- 交易时间按 Tenant 时区截断到整点，并且必须位于展会日期范围内。
- 交易币种来自 Tenant 默认币种，写入时作为历史快照保存。
- 商品销量按有效售出批次的当前明细统计；原始和更正库存流水保留但不重复进入业务报表。
- 展会结余等于交易收入减已登记支出，不包含商品成本；支出币种在创建时取 Tenant 默认币种。
- 库存操作在数据库事务中执行，库存不足时整批回滚。
- 数据库迁移使用前向 Flyway 迁移；V8 清理了旧版通用销售和文件导入结构。

## 验证

```bash
cd backend && ./mvnw spotless:apply && ./mvnw spotless:check && ./mvnw test
npm --prefix frontend run format && npm --prefix frontend run format:check
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test:run
npm --prefix frontend run build
npm --prefix frontend run pwa:check
```

## 文档

- [架构](docs/architecture.md)
- [数据库结构](docs/database-schema.md)
- [安全与租户隔离](docs/security-and-tenancy.md)
- [部署](docs/deployment.md)
- [商品图片存储](docs/r2-storage.md)
- [账号初始化](docs/account-bootstrap.md)
