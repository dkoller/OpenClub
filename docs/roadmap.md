# OpenClub delivery roadmap

**Last reviewed:** 2026-11-14 · **Status:** Foundation complete; membership MVP in progress · **Source of truth for open work:** [GitHub issues](https://github.com/dkoller/OpenClub/issues)

This roadmap inventories the work identified in the [original first-milestone plan](concepts/ai/first-milestone.md), [MVP proposal](concepts/product/mvp.md), accepted [stack ADR](decisions/0001-stack-selection.md), and [identity ADR](decisions/0002-identity-and-membership-model.md). The numbered **R01–R22** items are priority/dependency order, **not dates or commitments**. Each implementation PR stays below **1,000 added + deleted lines** and requires CI and human review.

## Priority rules and sequencing rationale

1. **P0 — required for a safe first working slice:** pilot intent, identity, authorization, backend and browser membership management, plus cross-club tests. Security guards precede exposed data-modifying endpoints, even when a demo could be built faster without them.
2. **P1 — required to validate and operate the MVP responsibly:** invitations if needed, browser tests, privacy workflows, hosting/backups, pilot evaluation and measuring AI collaboration. These can be split into small PRs and some run in parallel, but pilot release requires the relevant privacy/operations work.
3. **P2 — explicitly after MVP evidence:** agent orchestration and broad product expansion. Their value and ongoing costs are still unproven.

Dependency references indicate when implementation **must not outpace prerequisites**. Work within a phase may proceed in parallel when dependencies permit.

## Completed: foundations (merged PRs)

| Step | Status | Evidence | Why it came first |
| --- | --- | --- | --- |
| C01. Agent development/coordination concept | ✅ Done | [PR #1](https://github.com/dkoller/OpenClub/pull/1) | Agree how humans and AI collaborate before many agents edit code. |
| C02. MVP scope and project conventions | ✅ Done | [PR #2](https://github.com/dkoller/OpenClub/pull/2) | Constrain product scope to one club-management use case. |
| C03. Technology stack ADR-0001 | ✅ Done | [PR #3](https://github.com/dkoller/OpenClub/pull/3) | Choose React/TS, Kotlin/Ktor, jOOQ/PostgreSQL and Flyway before scaffolding. |
| C04. Ponytail-inspired agent rules and PR-size CI | ✅ Done | [PR #4](https://github.com/dkoller/OpenClub/pull/4) | Keep agent changes small, testable and reviewable. |
| C05. Historical increase to fewer than 2,000 lines | ✅ Done (superseded) | [PR #5](https://github.com/dkoller/OpenClub/pull/5) | Temporarily accommodated generated build artifacts; the current policy above is stricter. |
| C06. Runnable API/web/database scaffold and CI | ✅ Done | [PR #6](https://github.com/dkoller/OpenClub/pull/6) | Establish a tested executable baseline before domain features. |
| C07. Cross-platform one-command Docker Compose | ✅ Done | [PR #7](https://github.com/dkoller/OpenClub/pull/7) | Let contributors run the stack consistently on Windows, macOS and Linux. |
| C08. Pinned Gradle wrapper and pnpm lockfile | ✅ Done | [PR #8](https://github.com/dkoller/OpenClub/pull/8) | Make builds reproducible before feature contributions. |
| C09. Person/identity/membership schema and ADR-0002 | ✅ Done | [PR #9](https://github.com/dkoller/OpenClub/pull/9) | Model multi-club roles correctly before security and API implementation. |

**Decision update (2026-11-14):** [ADR-0003](decisions/0003-oidc-session-proposal.md) confirms Keycloak OIDC + Kotlin BFF architecture; [ADR-0004](decisions/0004-member-policy.md) records onboarding, directory visibility and lifecycle direction. Pilot validation, minor-contact privacy, session/CSRF details, initial admin implementation and API acceptance remain open. These decisions guide R01–R18 without implying completion.

**Important limitation:** The schema is not an authorization system. No member-management API or login flow has yet been implemented. The next items must establish these before handling real member data.

**Current review budget:** maximum 999 added + deleted lines (target 500–700); this supersedes the historical C05 limit. All tracked issues use this limit.

## Open work: numbered GitHub issues

### A. **Decisions and contracts.** First establish who the pilot is, what personal data is necessary, how identity is verified and the language-neutral API contract. These decisions prevent rework and unsafe endpoint design.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R01 | **P0** | [#10 — Validate pilot use case](https://github.com/dkoller/OpenClub/issues/10) | Sets the concrete user workflow and minimum data requirements. | — |
| R02 | **P0** | [#11 — Choose OIDC provider and sessions](https://github.com/dkoller/OpenClub/issues/11) | Identity security must be decided before authenticated endpoints. | — |
| R03 | **P0** | [#12 — Privacy/consent/retention rules](https://github.com/dkoller/OpenClub/issues/12) | Defines lawful, minimal data collection before member records are used. | R01 |
| R04 | **P0** | [#13 — Versioned OpenAPI contract](https://github.com/dkoller/OpenClub/issues/13) | Gives frontend/backend shared API and error semantics before coding. | R01, R02 |

### B. **Secure access foundation.** Verify identities and authorization before writing public CRUD routes. The initial admin bootstrap is security-sensitive and must precede normal club management.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R05 | **P0** | [#14 — OIDC integration and identity resolution](https://github.com/dkoller/OpenClub/issues/14) | Verified authentication is prerequisite to trusting the acting user. | R02, R03 |
| R06 | **P0** | [#15 — Club authorization guard](https://github.com/dkoller/OpenClub/issues/15) | Enforces the central multi-club security boundary first. | R05 |
| R07 | **P0** | [#16 — First-admin bootstrap](https://github.com/dkoller/OpenClub/issues/16) | Avoids an unauthorized user claiming a club or locking out its only admin. | R05, R06 |
| R08 | **P0** | [#17 — Club REST endpoints](https://github.com/dkoller/OpenClub/issues/17) | Reuses verified identity and tenant guard instead of launching insecure routes. | R04, R06, R07 |

### C. **Membership backend.** Implement the smallest club-member capabilities in order: add/list/search before role mutation, with invitations separated because their shape depends on the pilot.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R09 | **P0** | [#18 — Member add/list/search REST API](https://github.com/dkoller/OpenClub/issues/18) | Delivers core club volunteer value using the secured club APIs. | R01, R04, R06, R08 |
| R10 | **P0** | [#19 — Role changes and last-admin protection](https://github.com/dkoller/OpenClub/issues/19) | Adds sensitive administration only after membership basics exist. | R04, R06, R09 |
| R11 | **P1** | [#20 — Invitation/account linking](https://github.com/dkoller/OpenClub/issues/20) | Depends on pilot signup choice and reliable identity mapping. | R01, R03, R05, R09 |

### D. **Usable browser workflow.** Wire authenticated club navigation before exposing administration screens. Reuse agreed API contracts and test loading/denial states.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R12 | **P0** | [#21 — Frontend sign-in and club switcher](https://github.com/dkoller/OpenClub/issues/21) | Allows safe navigation only among authorized clubs. | R05, R08 |
| R13 | **P0** | [#22 — Club management screen](https://github.com/dkoller/OpenClub/issues/22) | Connects secure bootstrap/club endpoints to a usable flow. | R07, R08, R12 |
| R14 | **P0** | [#23 — Member/role management screen](https://github.com/dkoller/OpenClub/issues/23) | Completes the first visible end-to-end administrator workflow. | R09, R10, R12, R13 |

### E. **Release assurance and pilot.** Strengthen isolation tests before trusting the workflow. Add browser tests, data lifecycle, operational guidance and only then a non-sensitive demo and pilot evaluation.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R15 | **P0** | [#24 — Authorization/isolation hardening tests](https://github.com/dkoller/OpenClub/issues/24) | Must gate pilot use; cross-club data leaks are release blockers. | R06, R08–R10 |
| R16 | **P1** | [#25 — Playwright end-to-end journey](https://github.com/dkoller/OpenClub/issues/25) | Tests the user story after the API and screens are functional. | R12–R15 |
| R17 | **P1** | [#26 — Export/deletion/audit basics](https://github.com/dkoller/OpenClub/issues/26) | Makes initial member-data handling responsible and reviewable. | R03, R09, R10, R15 |
| R18 | **P1** | [#27 — Deployment cost/backups/restore](https://github.com/dkoller/OpenClub/issues/27) | Needed before real club data or long-running hosting. | R02, R03, R17 |
| R19 | **P1** | [#28 — Safe demo and pilot validation](https://github.com/dkoller/OpenClub/issues/28) | Measures actual utility after features, tests and operations are in place. | R01, R14–R18 |

### F. **AI development experiment.** Run a focused two-agent implementation/review trial after a usable vertical slice exists, so the comparison has meaningful metrics.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R20 | **P1** | [#29 — AI reviewer/worktree experiment](https://github.com/dkoller/OpenClub/issues/29) | Measure agent ROI on a real feature rather than scaffolding. | R19 |

### G. **Post-MVP choices.** Only consider orchestration and additional club/sport features after evidence; avoid scope creep in the first pilot.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R21 | **P2** | [#30 — Evaluate orchestration automation](https://github.com/dkoller/OpenClub/issues/30) | Avoid new coordination infrastructure without evidence. | R20 |
| R22 | **P2** | [#31 — Triage post-MVP backlog](https://github.com/dkoller/OpenClub/issues/31) | Keep speculative features outside initial delivery until pilot evidence. | R19 |

## MVP acceptance gate

The first pilot release is **not done** merely because the database tables or screens exist. Before real club/member data is used, verify: secured OIDC identity and first-admin creation; club-specific authorization for reads and writes; role and last-admin protections; safe data export/deletion/audit expectations; repeatable PostgreSQL and browser integration checks; documented hosting, backup/restore and costs; a synthetic demo accepted by the pilot. Record validation and follow-ups under [R19 / #28](https://github.com/dkoller/OpenClub/issues/28).

## Delivery and maintenance conventions

- Each **R** item is a tracked GitHub issue with acceptance criteria and dependencies. Update its checklist/close it only after merged PR evidence; open a follow-up issue for newly identified work.
- **Completed C items are not duplicated as open issues:** their merged PRs are the historical record.
- The older [first-milestone document](concepts/ai/first-milestone.md) is retained for historical context; **this file is the current numbered plan**.
- Scope exclusions until pilot evidence: payments, accounting, events, competitions, integrations, chat, pushes, federation sync, nonprofit generalization and a custom multi-agent framework.
- No tentative target dates or paid infrastructure commitments are implied by these priorities.

### R05 JWKS refresh implementation slice (review pending)

The code-exchange runtime now supports bounded refresh of the startup-vetted
JWKS URL, five-minute key expiry and a shared 30-second attempt cooldown.
See [refresh policy and remaining gates](security/oidc-provider-discovery.md#jwks-runtime-refresh-2026-11-14).
This slice does not complete R05: real provider rotation/browser E2E, session
endpoint verification, admission rate limits and expired-record cleanup remain.
### Session HTTP implementation note (2026-11-14)

Following #64, opt-in `/api/v1/auth/me` and `/api/v1/auth/logout` are implemented.
Logout requires exact Origin, JSON `{}` and session-bound CSRF, with transactional
validation/revocation before cookie clearing. See [session API](security/session-api.md).
R05 remains open pending CI, independent review, Keycloak E2E, JWKS freshness,
rate limits and cleanup; club authorization, bootstrap and privacy gates remain.
