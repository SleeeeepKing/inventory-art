---
name: inventory-art-verify-change
description: Finish and verify changes in the inventory-art repository by deriving affected areas from the diff, running every repository-mandated formatter and check, reviewing the final diff, and reporting exact results. Use for requests such as "检查改动并完成验证", "确认是否全部完成", "修完后验收", "run all checks", or "finish this change". Do not use for planning-only work, diagnosis before a fix exists, committing or production deployment.
---

# Verify Inventory Art Changes

## Required inputs

- Use the requested behavior, current worktree, and any user-stated verification limits.
- Locate the repository root and read every applicable `AGENTS.md` before deciding commands.
- Treat existing tracked and untracked changes as user-owned unless the current task clearly created them.

## Workflow

1. Inspect `git status --short`, the relevant diff, and recent changes needed to understand scope.
2. Compare the implementation with the request. Complete only small, clearly in-scope corrections; report a larger missing feature instead of silently expanding scope.
3. Classify every changed file by area. Read current scripts and CI configuration instead of relying on remembered commands.
4. Run the formatter required by `AGENTS.md` for every changed area before verification. Never format generated files, dependencies, build output, environment files, or lock files unless the task intentionally changes a lock file.
5. Run all checks required by `AGENTS.md` for each affected code area. Add focused checks when the change has a risk not covered by the standard suite.
6. Run `git diff --check` and self-review the complete diff, including untracked files. Check for:
   - unrelated edits, debug code, temporary files, secrets, and accidental generated output;
   - missing tests or documentation for changed behavior;
   - unsynchronized user-facing translations;
   - Tenant isolation regressions for business APIs;
   - unsafe edits to existing Flyway migrations;
   - API or authenticated-data caching when PWA behavior changes.
7. Re-run any check invalidated by a correction. Do not claim success from an earlier run against different content.

## Failure handling

- Preserve the exact failing command and the useful error excerpt.
- Diagnose from source, logs, and configuration; never label a platform or dependency failure by guesswork.
- Fix in-scope failures and rerun the affected checks. If the failure is environmental or pre-existing, demonstrate that distinction and report the blocker without weakening a check.
- Never delete user data, reset the worktree, rewrite migrations, or change production state to make verification pass.

## Stop and completion conditions

Stop successfully only when the requested behavior is present, all required formatters and checks pass on the final content, and the diff review finds no unresolved issue. Stop as blocked when a required check cannot run safely or needs unavailable external state; identify the exact missing prerequisite.

## Final report

Report:

- what was verified or corrected;
- changed files or areas;
- every formatter and verification command with pass/fail/skipped status and test counts when available;
- `git diff --check` and self-review outcome;
- warnings, skipped checks, environmental blockers, and remaining manual validation;
- whether the worktree still contains uncommitted changes.

Do not commit, push, open a PR, merge, or deploy unless the user separately authorizes that action.
