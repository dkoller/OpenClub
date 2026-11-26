# Documentation maintenance for every PR

**Policy:** Documentation is part of the implementation, not a later cleanup task. Read [root agent rules](../../AGENTS.md) and use the [PR template](../../.github/pull_request_template.md). This requirement applies to human and AI-authored PRs, including dependency updates.

## Before opening a PR

1. Identify what changed: API or schema, login/permissions, UI flow, command, dependency/tool version, configuration, hosting or a recorded decision.
2. Search for stale references in `README.md`, `docs/`, `AGENTS.md`, the OpenAPI contract and examples. Check that instructions match the **current** repository and CI, not a historical proposal.
3. Update affected documentation and tests **in the same PR**. Record command checks honestly; do not claim they ran when they did not.
4. In the PR description, list updated documents and the impact, or explicitly write **`Documentation: no changes needed`** with a brief reason. The template contains a required review checklist.
5. Preserve old ADRs, original proposals and research notes as history; add a dated implementation note or a new ADR to explain changes, rather than silently presenting historical discussion as current architecture.
6. Keep each PR at most **5,000 added + deleted lines**. The owner raised the historical 999-line limit on 2026-11-15. Split major changes into independently valid PRs; never omit necessary docs or tests.

## Impact checklist

| When changing... | Review/update... |
| --- | --- |
| Endpoints, request/response DTOs or authorization | `docs/contracts/`, relevant ADRs, API examples, developer docs |
| PostgreSQL, Flyway, jOOQ or backend test infrastructure | API `AGENTS.md`, ADR implementation notes, database/run commands and CI docs |
| Frontend navigation, user-facing behavior or tooling | Web `AGENTS.md`, UI docs, setup/test commands, contract usage |
| A feature a club member uses | The role guide(s) in [`docs/guides/`](../guides/README.md) for everyone who uses it (members and parents, coaches, treasurer, administrators) |
| Configuration, environment variables, operator commands, retention or deployment | The [operator guide](../operations/operator-guide.md) and the detailed runbook it links to |
| Package or tool versions (including Dependabot PRs) | Version-specific instructions, lockfile, CI/Docker compatibility and relevant docs **if affected** |
| Docker, secrets, environment variables or CI workflow | `docs/concepts/ai/one-command-run.md`, `scaffold.md`, `ci-optimization.md`, README |
| Accepted architecture or product scope | ADR (or explicit follow-up ADR), `docs/roadmap.md`, README and affected design docs |
| Exploratory research | Label it **draft/proposed** until a decision is accepted; record open questions without claiming completion |

**No documentation change is also a valid outcome**, e.g. a lockfile-only dependency patch with unchanged documented commands and passing CI. Explain the review in the PR so reviewers can check the conclusion.

## Current sources of truth

For live delivery gates, start with the [short current roadmap](../roadmap.md) and open issues. Detailed dated implementation notes and the original R01–R22 planning tables are preserved in the [roadmap archive](../history/roadmap-2026-11-17.md). Historical pages should link to the current acceptance gate rather than accumulate a second live status ledger.

- [Roadmap and live issues](../roadmap.md): priorities and actual task status, not the historical [first-milestone sketch](../concepts/ai/first-milestone.md).
- [ADR-0001](../decisions/0001-stack-selection.md) and [ADR-0005](../decisions/0005-single-club-installation.md): accepted stack and **current one-club-per-installation** member/account boundaries. [ADR-0002](../decisions/0002-identity-and-membership-model.md) is **superseded**, retained only as historical context; do not implement its multi-club/global-person assumptions.
- [CI workflow](../../.github/workflows/build.yml): currently PostgreSQL service-based tests, **not** Testcontainers.
- [Application manifests](../../apps/web/package.json) and lockfile: actual installed versions (including Vitest), rather than stale version references in prose.

## Screenshots in the guides

The pictures in `docs/guides/images` are not taken by hand. `dev/auth-e2e/docs-screenshots.spec.js` starts from an
empty club, fills it with the fictional demo data (`dev/deployment/seed-demo-data.sql`), signs in as a parent
(phone size) and as the administrator (phone size for the coach pages, laptop size for administration) and
photographs each page in English. Sign-in pages are never photographed, and only the PNG files leave the CI job.

- **When:** the workflow `.github/workflows/docs-screenshots.yml` runs after the Keycloak browser journey has passed
  on `main`. A picture is replaced only when it differs by more than 3% of its pixels, so a date that moved by a day
  does not count, while a changed screen does. If any picture changed, the workflow opens or updates one pull request
  *Update documentation screenshots* (branch `docs-screenshots`) that contains only PNG files; it is merged like any
  other docs PR. A pull request that changes the screenshot test itself uploads the pictures for review instead.
- **Repository setting:** with *Settings → Actions → General → Allow GitHub Actions to create and approve pull
  requests* on, the workflow opens the PR itself; with it off, it pushes the branch `docs-screenshots` and leaves a
  warning, and a person opens the PR from that branch. Pull requests created by the workflow token start no other
  workflows; the picture-only PR needs none.
- **Sizes:** phone pictures are 390 wide and show the whole page as one tall screen (at most 4000 pixels), with the tab
  bar at the bottom; laptop pictures are 1280 wide and show the whole page.
- **Every view:** the spec photographs every page of the app (34 pictures, listed in `docs/guides/screens.md`). A new
  page needs a `shoot(...)` line there and an entry in `screens.md`.
- **New picture:** add a `shoot(page, '#/…', 'name')` line to the spec and reference `images/name.png` from the guide;
  the next run on `main` adds the file. Keep names flat (`a-z`, digits, `-`), as the workflow refuses anything else.
- **Run locally:** with the Keycloak stack of the browser journey running, from `dev/auth-e2e`:
  `OPENCLUB_E2E_DISPOSABLE=true pnpm exec playwright test -c docs-screenshots.config.js --update-snapshots=all`
  (it bootstraps the club, so it needs an empty disposable database).
