# Repository Agent Guidelines

## Scope

These rules apply to the entire repository.

## Formatting

- Keep changes focused on the requested behavior; do not mix formatting with unrelated refactors.
- Before finishing any task, run the formatter for every area you changed.
- For backend Java changes, run:
  - `cd backend && ./mvnw spotless:apply`
  - `cd backend && ./mvnw spotless:check`
- For frontend, JSON, YAML, CSS, or Markdown changes, run:
  - `npm --prefix frontend run format`
  - `npm --prefix frontend run format:check`
- Never format generated files, build output, dependencies, environment files, or lock files.

## Verification

- Backend changes: run `cd backend && ./mvnw test`.
- Frontend changes: run `npm --prefix frontend run lint`, `typecheck`, `test:run`, and `build`.
- Report formatter and verification results before handing work back.

## Project Invariants

- Keep all user-facing frontend copy synchronized across English, Simplified Chinese, and French. Default first-login language remains English.
- Treat mobile use as the primary UI constraint. Check narrow screens, touch targets, and long French labels for user-facing layout changes.
- Preserve Tenant isolation in every business query and write. Add cross-Tenant denial coverage when introducing a new resource relationship or endpoint.
- Use new forward-only Flyway migrations for schema changes. Do not edit an applied migration or delete/transform production data without explicit confirmation and a recovery plan.
- Keep authenticated API responses, Tenant data, and private R2 URLs out of PWA caches. Do not add automatic retries for write requests.
- Keep the backend suitable for Railway Serverless within a 512 MiB limit; avoid unbounded in-memory work and background activity that prevents sleeping.

## Delivery

- Match the user's requested Git and release target exactly. Do not introduce a PR when direct `main` delivery was requested, and do not merge or deploy when only a commit or PR was requested.
- Require explicit authorization before production deployment, destructive database or object-storage operations, rollback, force-push, or a smoke test that writes production data.
- Report migrations, local verification, remote CI, deployment health, manual checks, and final worktree state separately.
