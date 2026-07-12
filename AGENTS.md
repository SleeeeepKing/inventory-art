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
