---
name: inventory-art-prod-debug
description: Diagnose inventory-art locally while using production-like configuration or explicitly authorized production PostgreSQL and Cloudflare R2, with profile, CORS, Cookie, Flyway, credential, trace, and real-write safety checks. Use for requests such as "本地启动 prod 调试", "IDEA 连接生产数据库和 R2", "完全模拟生产环境排错", "debug production R2 locally", or "检查 local-prod-debug 配置". Do not use for normal local development with Docker/MinIO, generic production deployment, or unrelated application bugs that do not involve production-backed local configuration.
---

# Debug a Production-Backed Local Run

## Required inputs and safety boundary

- Establish the symptom, local launch method, services that should remain local, and whether production PostgreSQL or R2 access is explicitly intended.
- Read current `README.md`, application profiles, safety validators, storage configuration, and deployment documentation. Never rely on copied environment values from an earlier task.
- Never print, commit, or store secrets. Refer to variable names and redact values in commands and reports.
- State before execution that production-backed startup can run Flyway and that application actions can modify real database rows or objects. Obtain confirmation before starting against production when the user has not already explicitly requested that scope.

## Workflow

1. Inspect the worktree and establish a clean code/config baseline. Do not mix debugging with unrelated local edits.
2. Preserve production safety rules. Use the repository’s dedicated local production-debug profile when it exists; do not weaken the normal production profile to allow localhost.
3. Build the required variable checklist dynamically from configuration classes, profile files, README, and deployment docs. Verify names and non-secret shape only.
4. Check the effective configuration:
   - active profile order and overrides;
   - exact frontend origin, API base URL, CORS origin, Cookie security, and SameSite behavior;
   - database host and SSL mode;
   - storage provider, account endpoint, region, exact private Bucket name, and Bucket-scoped permissions;
   - whether the IDE actually loads the intended environment variables.
5. Start with read-only evidence: application startup logs, health endpoint, safe database queries, Bucket metadata or object HEAD/list checks, browser Network details, backend `traceId`, and platform logs. Do not upload, migrate, edit, or delete production data as a connectivity probe without explicit confirmation.
6. Reproduce one narrow request and correlate frontend response, HTTP status/error code, backend trace, and provider response. Treat CORS, authentication, application validation, endpoint mismatch, Bucket mismatch, and provider failure as separate hypotheses.
7. Decide whether the evidence indicates configuration, platform state, stale frontend/PWA content, or code. Do not patch code to compensate for an unverified platform setting.
8. If a code fix is requested and supported by evidence, implement the smallest fix with regression coverage and use `$inventory-art-verify-change`. Do not commit or deploy unless separately authorized.

## Failure handling

- Quote the safe error code, status, trace ID, and operation; redact credentials, tokens, cookies, account IDs when sensitive, and signed URLs.
- If the provider or platform cannot be queried, say which fact remains unknown and what read-only evidence the user should obtain.
- Never bypass production validation, disable TLS, broaden CORS to `*`, make a private Bucket public, disable Flyway checks, or invent a success result.
- If a write may have succeeded despite a lost response, refresh/read the authoritative state before any retry.

## Stop and completion conditions

Stop when the root cause is supported by correlated evidence and either the safe correction is verified or the exact external/manual blocker is documented. Do not continue changing layers after the failing boundary is isolated.

## Final report

Report:

- effective local/profile topology and which production services were touched;
- evidence and root cause, separating confirmed facts from remaining hypotheses;
- configuration or code changes without secret values;
- read-only and write validations actually performed;
- Flyway, real-data, Bucket, CORS, Cookie, and stale-PWA risks;
- commands/checks run, unresolved platform steps, and whether deployment is still required.
