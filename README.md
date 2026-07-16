# Inventory Art

[简体中文](docs/README.zh-CN.md)

Inventory Art is a multi-tenant sales and inventory reporting system for art
and merchandise vendors at events. It is designed for entering facts recorded
on paper after an event:

- transactions: event, sales hour, and amount;
- product sales: event, product variant, and quantity sold.

The application does not store customer identities, payment methods, payment
status, refunds, sales channels, or order-to-product links, and it does not
import third-party transaction files.

## Features

- Rapid transaction entry for batches of up to 100 positive amounts.
- Transaction search, editing, and deletion by event, date, and order number.
- Product families and variants with stock adjustments and exact corrections.
- Event-based product sales with reversible inventory movements.
- Event expenses with tenant-shared custom categories.
- Revenue, expense, balance, time-series, and product-volume reports.
- Tenant isolation with English, Simplified Chinese, and French interfaces.
- Local filesystem or S3-compatible private product-image storage.

Catalog prices and costs are descriptive product data only. They are not used
to infer actual sales revenue.

## Technology

- Backend: Java 21, Spring Boot, Spring Security, Spring Data JPA, Flyway, and
  PostgreSQL.
- Frontend: Vue 3, TypeScript, Vite, Pinia, Element Plus, and ECharts.
- Storage: local filesystem, Cloudflare R2, MinIO, or another S3-compatible
  provider.
- Tests: JUnit, Testcontainers, and Vitest.

## Local development

Copy the placeholder-only environment file and start the complete stack:

```bash
cp .env.example .env
docker compose up --build
```

Default local endpoints:

- Frontend: `http://localhost:4173`
- Backend: `http://localhost:8080`
- OpenAPI UI: `http://localhost:8080/swagger-ui.html`
- MinIO console: `http://localhost:9001`

To run the application processes separately:

```bash
docker compose up -d postgres minio minio-init
cd backend && ./mvnw spring-boot:run
npm --prefix frontend install
npm --prefix frontend run dev
```

### Production-backed local debugging

The dedicated `local-prod-debug` profile may be combined with `prod` only when
access to the real production PostgreSQL database and R2 bucket has been
explicitly authorized:

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

Run the frontend with
`VITE_API_BASE_URL=http://localhost:8080/api/v1`. This profile permits only an
exact localhost or `127.0.0.1` HTTP origin and relaxes local cookie transport;
production JWT, R2, seed, and configuration checks remain active.

This mode can read and write real production data, and startup can run Flyway
migrations. Never store production credentials in repository files. See
[account bootstrap](docs/account-bootstrap.md) for the initial administrator
workflow.

## Configuration

| Variable                                                 | Purpose                                                  |
| -------------------------------------------------------- | -------------------------------------------------------- |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL connection                                    |
| `JWT_SECRET`                                             | JWT signing secret; use a strong random production value |
| `CORS_ALLOWED_ORIGINS`                                   | Explicit frontend origins allowed to call the API        |
| `COOKIE_SECURE`                                          | Must be `true` for production HTTPS                      |
| `STORAGE_PROVIDER`                                       | `local`, `r2`, or `minio`                                |
| `LOCAL_STORAGE_PATH`                                     | Local product-image directory                            |
| `R2_ENDPOINT`, `R2_PUBLIC_ENDPOINT`                      | S3-compatible API and upload endpoints                   |
| `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`               | Object-storage credentials                               |
| `R2_BUCKET_PRIVATE`                                      | Private product-image bucket                             |
| `R2_PRESIGNED_URL_EXPIRATION_SECONDS`                    | Presigned upload lifetime                                |
| `VITE_API_BASE_URL`                                      | Frontend build-time API base URL                         |

Production credentials must be provided by the deployment platform's secret
store. The repository's `.env.example` contains local defaults and placeholders
only. See the complete [deployment guide](docs/deployment.md).

## Data-model invariants

- Every transaction and product-sale batch belongs to an event.
- Transaction time is truncated to an hour in the tenant timezone and must be
  inside the event date range.
- Transaction currency is copied from the tenant default as a historical
  snapshot.
- Product sales are calculated from the current effective sale-batch lines;
  original and corrective inventory movements remain auditable.
- Event balance is recorded revenue minus recorded expenses and excludes
  product cost.
- Inventory changes are transactional; insufficient stock rolls back the whole
  batch.
- Database changes use forward-only Flyway migrations.

## Verification

```bash
cd backend && ./mvnw spotless:apply && ./mvnw spotless:check && ./mvnw test
npm --prefix frontend run format && npm --prefix frontend run format:check
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test:run
npm --prefix frontend run build
npm --prefix frontend run pwa:check
```

## Documentation

- [Architecture](docs/architecture.md)
- [Database schema](docs/database-schema.md)
- [Security and tenant isolation](docs/security-and-tenancy.md)
- [Deployment](docs/deployment.md)
- [Product-image storage](docs/r2-storage.md)
- [Account bootstrap](docs/account-bootstrap.md)
- [Security policy](SECURITY.md)

## Security

Please report vulnerabilities privately through
[GitHub Security Advisories](https://github.com/SleeeeepKing/inventory-art/security/advisories/new).
Do not publish credentials, private object URLs, or production data in an issue.

## License

The source code and documentation are available under the
[MIT License](LICENSE). The GPT-generated Inventory Art logo and its derived
application icons are licensed separately under CC BY 4.0; see
[Asset licensing](ASSETS.md).
