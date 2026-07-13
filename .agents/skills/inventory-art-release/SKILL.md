---
name: inventory-art-release
description: Publish verified inventory-art changes through the user-requested Git mode, then monitor CI, Cloudflare Pages, Railway, health checks, and repository state until the requested release is proven complete. Use for requests such as "验证完成就提交部署", "提交推送 main", "发布到生产", "open a PR and deploy", or "commit, push, and verify deployment". Do not use for local verification only, planning, ordinary Git questions, or when the user has not authorized a commit, push, merge, or production deployment.
---

# Release Inventory Art

## Required inputs and authority

- Require an explicit delivery request. Treat “push to main”, “open a PR”, and “deploy to production” as different scopes.
- Follow the exact requested target. Do not insert a PR when direct `main` delivery was requested, and do not merge or deploy when only a commit or PR was requested.
- Before force-push, rollback, destructive database work, production data writes, or an unrequested merge, obtain explicit confirmation.

## Prepare the release

1. Read applicable `AGENTS.md`, `docs/deployment.md`, current CI workflows, deployment configuration, remotes, branch tracking, and `git status`.
2. Review the complete diff and recent commits. Separate unrelated user changes; never bundle them for convenience.
3. Apply the `$inventory-art-verify-change` workflow to the final release content. Do not publish with failed or skipped required checks unless the user explicitly accepts the documented risk.
4. Identify database migrations, configuration changes, build-time frontend variables, PWA changes, and deployment-order requirements. Read current files rather than copying old platform values.

## Publish exactly the requested mode

1. Confirm the commit scope and write a message that describes the behavior.
2. Stage only intended files and inspect the staged diff.
3. Commit and push to the requested branch.
4. If a PR was requested, create it with a concise summary, risk notes, migrations, and exact validation results. Do not silently change draft/ready status.
5. If production requires merging and the user authorized deployment, wait for required CI and preview gates before merging. If direct `main` was requested, monitor the checks triggered by that push.

Use the available GitHub connector first and authenticated `gh` only when necessary. Record connector or platform errors exactly and use a supported fallback; never report a failed tool call as success.

## Verify delivery and deployment

1. Confirm the remote branch or merged commit contains the intended commit.
2. Wait for repository CI and required preview checks. A local pass does not substitute for failed CI.
3. When production deployment is in scope, verify the actual Cloudflare Pages and Railway deployment for that commit, not merely that a push occurred.
4. Perform safe read-only checks from the current deployment documentation, including backend health and frontend availability. For PWA changes, verify published artifacts and cache boundaries when feasible.
5. Require separate confirmation before a production smoke test that creates, edits, uploads, or deletes real data. If no safe test account/session exists, report manual validation instead of fabricating success.
6. Finish by checking local branch tracking and worktree state.

## Failure handling

- Stop before merge or production promotion when a required gate fails.
- Distinguish repository CI, Cloudflare, Railway, DNS/CORS, credentials, migrations, and application health using returned evidence.
- Do not guess deployment state, retry writes that may have succeeded, expose secrets, bypass branch protection, or rewrite history to conceal a failure.
- If a deployment is unhealthy, preserve logs and commit identifiers, report the safest rollback option, and obtain confirmation before executing rollback or data-changing recovery.

## Completion conditions and report

Complete only when the requested Git action succeeded and every in-scope gate and deployment is verified, or when a clearly identified external blocker prevents completion.

Report:

- commit, branch, push, PR, and merge results as applicable;
- local checks and remote CI outcomes;
- Cloudflare Pages and Railway status tied to the released commit;
- health/smoke checks actually performed and any manual checks remaining;
- migration, rollback, and production-data implications;
- final local tracking and worktree state.
