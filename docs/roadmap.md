# OpenClub delivery roadmap

**Last reviewed:** 2026-11-15 · **Status:** R01 requirements complete; one-club-per-installation architecture chosen, implementation migration pending · **Source of truth for open work:** [GitHub issues](https://github.com/dkoller/OpenClub/issues)

This roadmap inventories the work identified in the [original first-milestone plan](concepts/ai/first-milestone.md), [MVP proposal](concepts/product/mvp.md), accepted [stack ADR](decisions/0001-stack-selection.md), and [identity ADR](decisions/0002-identity-and-membership-model.md). The numbered **R01–R22** items are priority/dependency order, **not dates or commitments**. Each implementation PR stays below **1,000 added + deleted lines** and requires CI and human review.

**Current architectural direction (2026-11-15):** **one club per OpenClub installation and independent database**; the historical multi-tenant roadmap entries below are superseded as described in the final section and proposed [ADR-0005](decisions/0005-club-owned-member-profiles.md). No club switcher or global person domain is part of the intended first release. Preserve R05 authentication through staged migration; do not assume the current schema already enforces one club.

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

**Decision update (2026-11-14):** [ADR-0003](decisions/0003-oidc-session-proposal.md) confirms Keycloak OIDC + Kotlin BFF architecture; [ADR-0004](decisions/0004-member-policy.md) records onboarding, directory visibility and lifecycle direction. R01 pilot requirements are now accepted (#10 closed; PRs #72–#73). R05 authentication is complete (#14 closed); privacy/minors' contact visibility, club authorization, first-admin implementation and club/member API acceptance remain open. These decisions guide R01–R18 without implying completion.

**Important limitation:** Opt-in OIDC authentication is implemented and tested with synthetic identities; the schema and login are not club authorization. Club authorization (R06), first-admin bootstrap (R07), privacy acceptance (R03) and the web proxy path integration are still required before real member data.

**Current review budget:** maximum 999 added + deleted lines (target 500–700); this supersedes the historical C05 limit. All tracked issues use this limit.

## Roadmap items: completed and outstanding work

### A. **Decisions and contracts.** First establish who the pilot is, what personal data is necessary, how identity is verified and the language-neutral API contract. These decisions prevent rework and unsafe endpoint design.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R01 ✅ complete | **P0** | [#10 — Validate pilot use case](https://github.com/dkoller/OpenClub/issues/10) | Sets the concrete user workflow and minimum data requirements. | — |
| R02 | **P0** | [#11 — Choose OIDC provider and sessions](https://github.com/dkoller/OpenClub/issues/11) | Identity security must be decided before authenticated endpoints. | — |
| R03 | **P0** | [#12 — Privacy/consent/retention rules](https://github.com/dkoller/OpenClub/issues/12) | Defines lawful, minimal data collection before member records are used. | R01 |
| R04 | **P0** | [#13 — Versioned OpenAPI contract](https://github.com/dkoller/OpenClub/issues/13) | Gives frontend/backend shared API and error semantics before coding. | R01, R02 |

### B. **Secure access foundation.** Verify identities and authorization before writing public CRUD routes. The initial admin bootstrap is security-sensitive and must precede normal club management.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R05 ✅ engineering complete | **P0** | [#14 — OIDC integration and identity resolution](https://github.com/dkoller/OpenClub/issues/14) | Verified authentication is prerequisite to trusting the acting user. | R02, R03 |
| R06 | **P0** | [#15 — Local permission guard](https://github.com/dkoller/OpenClub/issues/15) | Enforces trusted single-club and team-scoped authorization. | R05 |
| R07 | **P0** | [#16 — First-admin bootstrap](https://github.com/dkoller/OpenClub/issues/16) | Avoids an unauthorized user claiming a club or locking out its only admin. | R05, R06 |
| R08 | **P0** | [#17 — Single-club settings API](https://github.com/dkoller/OpenClub/issues/17) | Exposes only the installation's configured club under verified local authorization. | R04, R06, R07 |

### C. **Membership backend.** Implement the smallest club-member capabilities in order: add/list/search before role mutation, with invitations separated because their shape depends on the pilot.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R09 | **P0** | [#18 — Member add/list/search REST API](https://github.com/dkoller/OpenClub/issues/18) | Delivers core club volunteer value using the secured club APIs. | R01, R04, R06, R08 |
| R10 | **P0** | [#19 — Role changes and last-admin protection](https://github.com/dkoller/OpenClub/issues/19) | Adds sensitive administration only after membership basics exist. | R04, R06, R09 |
| R11 | **P1** | [#20 — Invitation/account linking](https://github.com/dkoller/OpenClub/issues/20) | Depends on pilot signup choice and reliable identity mapping. | R01, R03, R05, R09 |

### D. **Usable browser workflow.** Wire authenticated club navigation before exposing administration screens. Reuse agreed API contracts and test loading/denial states.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R12 | **P0** | [#21 — Frontend sign-in and single-club landing](https://github.com/dkoller/OpenClub/issues/21) | Authenticated local-club entry without a club switcher. | R05, R08 |
| R13 | **P0** | [#22 — Club management screen](https://github.com/dkoller/OpenClub/issues/22) | Connects secure bootstrap/club endpoints to a usable flow. | R07, R08, R12 |
| R14 | **P0** | [#23 — Member/role management screen](https://github.com/dkoller/OpenClub/issues/23) | Completes the first visible end-to-end administrator workflow. | R09, R10, R12, R13 |

### E. **Release assurance and pilot.** Strengthen isolation tests before trusting the workflow. Add browser tests, data lifecycle, operational guidance and only then a non-sensitive demo and pilot evaluation.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R15 | **P0** | [#24 — Authorization/isolation hardening tests](https://github.com/dkoller/OpenClub/issues/24) | Must gate pilot use; cross-club data leaks are release blockers. | R06, R08–R10 |
| R16 | **P1** | [#25 — Playwright end-to-end journey](https://github.com/dkoller/OpenClub/issues/25) | Tests the user story after the API and screens are functional. | R12–R15 |
| R17 | **P1** | [#26 — Export/deletion/audit basics](https://github.com/dkoller/OpenClub/issues/26) | Makes initial member-data handling responsible and reviewable. | R03, R09, R10, R15 |
| R18 | **P1** | [#27 — Deployment cost/backups/restore](https://github.com/dkoller/OpenClub/issues/27) | Needed before real club data or long-running hosting. | R02, R03, R17 |
| R19 | **P1** | [#28 — Safe demo and pilot validation](https://github.com/dkoller/OpenClub/issues/28) | Measures actual utility after features, tests and operations are in place. | R01, R14–R18, #74–#76 |

### F. **AI development experiment.** Run a focused two-agent implementation/review trial after a usable vertical slice exists, so the comparison has meaningful metrics.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R20 | **P1** | [#29 — AI reviewer/worktree experiment](https://github.com/dkoller/OpenClub/issues/29) | Measure agent ROI on a real feature rather than scaffolding. | R19 |

### G. **Post-MVP choices.** Only consider orchestration and additional club/sport features after evidence; avoid scope creep in the first pilot.

| Order | Priority | Issue / expected outcome | Why here | Dependencies |
| --- | --- | --- | --- | --- |
| R21 | **P2** | [#30 — Evaluate orchestration automation](https://github.com/dkoller/OpenClub/issues/30) | Avoid new coordination infrastructure without evidence. | R20 |
| R22 | **P2** | [#31 — Triage post-MVP backlog](https://github.com/dkoller/OpenClub/issues/31) | Evaluate additional features beyond already accepted team assignments and permissions after pilot evidence. | R19 |

## MVP acceptance gate

The first pilot release is **not done** merely because the database tables or screens exist. Before real club/member data is used, verify: secured OIDC identity and first-admin creation; club-specific authorization for reads and writes; role and last-admin protections; safe data export/deletion/audit expectations; repeatable PostgreSQL and browser integration checks; documented hosting, backup/restore and costs; a synthetic demo accepted by the pilot. Record validation and follow-ups under [R19 / #28](https://github.com/dkoller/OpenClub/issues/28).

## Delivery and maintenance conventions

- Each **R** item is a tracked GitHub issue with acceptance criteria and dependencies. Update its checklist/close it only after merged PR evidence; open a follow-up issue for newly identified work.
- **Completed C items are not duplicated as open issues:** their merged PRs are the historical record.
- The older [first-milestone document](concepts/ai/first-milestone.md) is retained for historical context; **this file is the current numbered plan**.
- Scope exclusions until pilot evidence: payments, accounting, events, competitions, integrations, chat, pushes, federation sync, and a custom multi-agent framework. Team assignments and configurable permission grants are **first-release requirements**, tracked separately in #75/#76; broader coaching/training functionality and general nonprofit workflows remain later decisions.
- No tentative target dates or paid infrastructure commitments are implied by these priorities.

### R05 authentication closeout (2026-11-15; merged)

Opt-in Keycloak OIDC login/callback, issuer-bound token validation, hashed PostgreSQL
sessions, `/auth/me`, CSRF-protected logout, issuer-pinned JWKS refresh, admission
limits, and indexed expiry cleanup are implemented. Real Chromium/Keycloak/PostgreSQL
login and RSA key-rotation journeys run in CI. See [browser login](security/oidc-browser-login.md),
[session API](security/session-api.md), [refresh policy](security/oidc-provider-discovery.md#jwks-runtime-refresh-2026-11-14),
[rate limits](security/auth-admission-limit.md), and [cleanup](security/auth-record-cleanup.md).

**R05/#14 is closed after merged PR #71.** The
R05 authentication milestone does not authorize public or member-data use: R02/#11
operational recovery, R03/#12 privacy, R06/#15 club-scoped authorization,
R07/#16 first-admin bootstrap and frontend `/api` proxy-path integration remain
separate release gates. The browser E2E runs directly against the API, not the proxy.


### R01 pilot requirements acceptance (2026-11-15; complete)

R01 [#10](https://github.com/dkoller/OpenClub/issues/10) closed after merged [PR #72](https://github.com/dkoller/OpenClub/pull/72) and [PR #73](https://github.com/dkoller/OpenClub/pull/73). The pilot administrator accepted a synthetic first-release scenario covering member management, team assignment and configurable club-wide/team-specific permissions. The first existing system to replace is Excel; the longer-term product goal is to replace fragmented club applications, not permanently integrate them. Four first-release UI languages are required: German, English, French and Italian.

**Current member data requirements:** first name, last name and date of birth required for everyone; both email and phone required for adults, optional for minors; gender required for players only when configured by that club. Exact age threshold and gender representation remain R03/R04 policy/design work. **Personal data is never shared, copied, synchronized or auto-filled across clubs**, even for the same authenticated identity. Shared authentication must not imply a shared personal directory. Existing [ADR-0002](decisions/0002-identity-and-membership-model.md) schema is not proof that this privacy boundary is implemented; data-model and authorization remediation are required before real member data.

**Tracked follow-up work:** [#74 — club-local personal-data model](https://github.com/dkoller/OpenClub/issues/74), [#75 — teams/assignments](https://github.com/dkoller/OpenClub/issues/75), and [#76 — configurable permission grants](https://github.com/dkoller/OpenClub/issues/76).

**Release scope changes requiring planning:** team assignments and granular permission grants are newly accepted first-release requirements and require dedicated contracts, implementation and tests; R09/R10 alone do not cover them. R03 privacy, R04 contracts, R06 authorization, R07 admin bootstrap, R15 isolation tests and R19 functional pilot demo are still open. Excel column inventory, imports and migration specifications remain deliberately deferred. R01 acceptance is **requirements acceptance only**, not runtime validation or legal/privacy approval.


### Single-club deployment decision (owner, 2026-11-15; proposed ADR-0005 in PR #79)

**One reusable OpenClub installation serves exactly one club, with its own PostgreSQL database and configuration.** A fresh installation provisions a single club through controlled first-admin setup. There is no multi-club tenant selection, in-app club switcher, global member directory or shared OpenClub login across installations. Existing OIDC authentication and database schema need staged migration, not destructive rewriting. Historical multi-club requirements elsewhere in this roadmap are **superseded for the first release**, even when retained to explain earlier work. R01/#10 stays closed for requirements discovery; the architecture revision is tracked by #74.

**Scope changes:** R07/#16 becomes one-time initial club/administrator setup; R08/#17 one-club settings/profile APIs rather than club creation/listing; R12/#21 sign-in and local landing without club switcher; R06/#15 local capability/team authorization without multi-tenant routing. R15/#24 must test singleton-club invariants, installation/database separation and privilege boundaries, rather than rely only on multi-club tests inside one database. R19/#28 must test the same accepted member/team/permission workflows within one club. #75 and #76 remain first-release requirements. R18/#27 must cover independent per-club hosting, costs, backups, upgrades and recovery.

**Gate:** Before implementing the changed endpoints or deleting legacy tables, resolve and test migration for existing databases (including any legacy multi-club state), accountless member creation, OIDC account linking, first-admin authorization and preservation of login/session behavior. R03 privacy and the unapproved R04 OpenAPI contract remain blockers to handling real member data. See proposed [ADR-0005](decisions/0005-club-owned-member-profiles.md) and [#74](https://github.com/dkoller/OpenClub/issues/74).
