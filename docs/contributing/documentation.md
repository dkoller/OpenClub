# Documentation maintenance for every PR

**Policy:** Documentation is part of the implementation, not a later cleanup task. Read [root agent rules](../../AGENTS.md) and use the [PR template](../../.github/pull_request_template.md). This requirement applies to human and AI-authored PRs, including dependency updates.

## Before opening a PR

1. Identify what changed: API or schema, login/permissions, UI flow, command, dependency/tool version, configuration, hosting or a recorded decision.
2. Search for stale references in `README.md`, `docs/`, `AGENTS.md`, the OpenAPI contract and examples. Check that instructions match the **current** repository and CI, not a historical proposal.
3. Update affected documentation and tests **in the same PR**. Record command checks honestly; do not claim they ran when they did not.
4. In the PR description, list updated documents and the impact, or explicitly write **`Documentation: no changes needed`** with a brief reason. The template contains a required review checklist.
5. Preserve old ADRs, original proposals and research notes as history; add a dated implementation note or a new ADR to explain changes, rather than silently presenting historical discussion as current architecture.
6. Check relative Markdown links and keep the diff strictly below **1,000 added + deleted lines**. Split major changes into independently valid PRs; do not omit necessary docs or tests to fit.

## Impact checklist

| When changing... | Review/update... |
| --- | --- |
| Endpoints, request/response DTOs or authorization | `docs/contracts/`, relevant ADRs, API examples, developer docs |
| PostgreSQL, Flyway, jOOQ or backend test infrastructure | API `AGENTS.md`, ADR implementation notes, database/run commands and CI docs |
| Frontend navigation, user-facing behavior or tooling | Web `AGENTS.md`, UI docs, setup/test commands, contract usage |
| Package or tool versions (including Dependabot PRs) | Version-specific instructions, lockfile, CI/Docker compatibility and relevant docs **if affected** |
| Docker, secrets, environment variables or CI workflow | `docs/concepts/ai/one-command-run.md`, `scaffold.md`, `ci-optimization.md`, README |
| Accepted architecture or product scope | ADR (or explicit follow-up ADR), `docs/roadmap.md`, README and affected design docs |
| Exploratory research | Label it **draft/proposed** until a decision is accepted; record open questions without claiming completion |

**No documentation change is also a valid outcome**, e.g. a lockfile-only dependency patch with unchanged documented commands and passing CI. Explain the review in the PR so reviewers can check the conclusion.

## Current sources of truth

- [Roadmap and live issues](../roadmap.md): priorities and actual task status, not the historical [first-milestone sketch](../concepts/ai/first-milestone.md).
- [ADR-0001](../decisions/0001-stack-selection.md) and [ADR-0002](../decisions/0002-identity-and-membership-model.md): accepted stack and member identity boundaries.
- [CI workflow](../../.github/workflows/build.yml): currently PostgreSQL service-based tests, **not** Testcontainers.
- [Application manifests](../../apps/web/package.json) and lockfile: actual installed versions (including Vitest), rather than stale version references in prose.
