# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately through this repository's
[GitHub Security Advisories](https://github.com/SleeeeepKing/inventory-art/security/advisories/new).
Do not include credentials, tokens, private object URLs, personal data, or
production records in a public issue.

Include the affected version or commit, a minimal reproduction, the expected
impact, and any safe mitigation you have identified. Please avoid testing that
changes production data, uploads objects, or degrades service availability.

## Supported versions

Security fixes are applied to the latest version on `main`. Older commits and
deployments are not supported unless explicitly documented.

## Operational secrets

Production credentials belong in the deployment platform's secret store. The
repository contains only placeholders and local-development defaults. If a
credential is ever committed, revoke and rotate it before removing it from Git
history.
