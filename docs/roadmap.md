# OpenClub delivery roadmap

**Last reviewed:** 2026-11-15 · **Status:** Single-club schema, operator bootstrap/recovery, verified sessions, complete single-club browser landing, internal member/team/grant and archive/restore foundations; member/team/grant HTTP workflows, privacy sign-off and production release remain open · **Source of truth for work:** [GitHub issues](https://github.com/dkoller/OpenClub/issues)

This roadmap inventories the work identified in the [original first-milestone plan](concepts/ai/first-milestone.md), [MVP proposal](concepts/product/mvp.md), accepted [stack ADR](decisions/0001-stack-selection.md), and [identity ADR](decisions/0002-identity-and-membership-model.md). The numbered **R01–R22** items are priority/dependency order, **not dates or commitments**. Each implementation PR uses at most **5,000 added + deleted lines** and requires applicable CI and review; the owner-authorized controlled development loop may merge after checks pass without a separate per-PR owner approval (see root `AGENTS.md`). Production/privacy/security approvals remain separate.

**Current architectural direction (2026-11-15):** **one club per OpenClub installation and independent database**; the historical multi-tenant roadmap entries below are superseded as described in the final section and accepted [ADR-0005](decisions/0005-single-club-installation.md). No club switcher or global person domain is part of the intended first release. PR #79 replaced the pre-production schema with a fresh singleton V1 and preserved R05 authentication; there is no backward-compatible migration for old development volumes.

## Implemented foundations since the single-club reset

- [PRs #82–#83](https://github.com/dkoller/OpenClub/pull/83): restricted first-admin bootstrap and sole-admin recovery **operator commands**, not web registration
- [PRs #84–#86](https://github.com/dkoller/OpenClub/pull/86): server-side local club authorization, proposed member contract, and internal-only name directory
- [PRs #87–#88](https://github.com/dkoller/OpenClub/pull/88): privacy decision inventory (not legal sign-off) and explicit club-wide capability checks
- [PRs #89–#92](https://github.com/dkoller/OpenClub/pull/92): internal team assignments, team-scoped capabilities, and reviewed serialized grant/revoke logic
- [PR #93](https://github.com/dkoller/OpenClub/pull/93): browser-only four-language preference persistence
- [PRs #164–#165](https://github.com/dkoller/OpenClub/pull/165): internal member archive/reactivation; [PR #167](https://github.com/dkoller/OpenClub/pull/167): database-enforced inactive-member access
- [PR #168](https://github.com/dkoller/OpenClub/pull/168): privacy-minimized team roster under dual directory/team grants, internal only
- [PRs #169–#170](https://github.com/dkoller/OpenClub/pull/170): real React/Vite/Keycloak browser sign-in, unlinked denial, operator bootstrap, authorized landing; R12/#21 closed
- [PRs #166 and #171](https://github.com/dkoller/OpenClub/pull/171): actual synthetic PostgreSQL restore CI and recovered Kotlin API startup evidence (not a production drill)

**Implementation versus acceptance:** The OpenAPI member/team/grant operations are still marked `NOT IMPLEMENTED` / `0.0.0-draft`, and the R03 inventory remains unapproved for live personal data. Closing a tracking issue does not itself approve handling real member records. Do not infer deployment readiness from internal PostgreSQL tests.

## Code-to-tracker reconciliation (2026-11-16; after PR #179)

**Verified executable boundaries:** Ktor registers authentication/session routes and ID-only `GET /api/v1/club`; the internal PostgreSQL member directory, create/update/archive/reactivation, team assignments, grants and role safeguards **are not registered as member/team/grant HTTP routes**. React currently provides sign-in/denial/logout and a DE/EN/FR/IT club landing, not member/team/permission administration. Real Chromium tests exercise OIDC/session/club landing and rotation, not full membership workflows. The stock Compose and nginx setup is localhost HTTP development infrastructure, **not deployed HTTPS**; `/health` and `/api/health` are liveness, not database readiness. Synthetic restore and recovered API startup are tested; a witnessed production recovery remains outstanding.

**Issue state reconciliation:** R03 [#12](https://github.com/dkoller/OpenClub/issues/12) and R04 [#13](https://github.com/dkoller/OpenClub/issues/13) are **open** again because privacy and contract acceptance are not complete. R07 [#16](https://github.com/dkoller/OpenClub/issues/16) remains **closed for operator-command implementation**; real-data operational approval and witnessed recovery drill are tracked separately in [#180](https://github.com/dkoller/OpenClub/issues/180) with [#27](https://github.com/dkoller/OpenClub/issues/27). R12 [#21](https://github.com/dkoller/OpenClub/issues/21) remains closed for the verified login/landing slice only. Existing open #17–#19, #22–#28 and #75–#76 retain incomplete HTTP, UI, test and operating work. No M1 deployment or M2 production sign-off is claimed.

**Active policy:** Maximum 5,000 additions + deletions per PR; controlled development-loop auto-merges only after applicable CI, Ponytail/appropriate review and preserved security/privacy gates. Historic issue paragraphs referring to 1,000 lines, human approval for every PR, global person or club switching are superseded by [AGENTS.md](../AGENTS.md) and ADR-0005, not additional requirements.

## Two delivery milestones and critical path (2026-11-16)

**Milestone M1 — deployed synthetic-data demo (no real member data).** A reachable, reproducibly deployed one-club installation with configured OIDC and TLS, where an authorized administrator can use the actual React UI to create, search, edit, and archive synthetic members, assign them to teams, and delegate/revoke club/team capabilities in DE/EN/FR/IT. Verify full browser/API authorization-denial and validation flows with synthetic fixtures. Record the deployment URL or environment, configuration boundaries, test evidence and known limitations in R19/#28. M1 is **not** privacy approval or authorization to store real names, contact details, or birth dates.

**Milestone M2 — real-club production pilot.** M1 plus approved R03 privacy/legal/retention/minors policy and R04 field/access contract, tested export/erasure/audit paths, independent security and operator-recovery acceptance, documented operator identity and responsibilities, production TLS/secrets/OIDC/monitoring, encrypted off-site backups and a witnessed restore/upgrade/rollback drill. Explicit owner/operator release acceptance is required before any real member data. Neither a green CI badge nor M1 automatically promotes the system to M2.

### Ordered delivery batches (execution priority, not deadlines)

| Batch | Concrete completion | Primary tracking |
| --- | --- | --- |
| **A — decisions and secured API** | Resolve minimum R03/R04 decisions before exposing personal fields; deliver authorized club settings, member CRUD/archive, teams/assignments and grant/revoke HTTP routes with CSRF, rate/size and negative authorization tests. | #12, #13, #15, #17–#19, #75, #76 |
| **B — usable browser MVP** | Club settings plus accessible member list/search/create/edit/archive, team assignments, scoped permission management and complete DE/EN/FR/IT coverage. | #22, #23, #75, #76 |
| **C — assurance and lifecycle** | Browser journeys, SQL/API privilege regressions, last-admin safety, safe export/deletion and audit, all with synthetic data. Security reviews remain gates. | #16, #24–#26 |
| **D — deployment and acceptance** | Reproducible no-paid-service-required synthetic environment with TLS/OIDC, health checks, restart/persistence, operational evidence and M1 acceptance; separately validate production costs, backup/restore/upgrade and real-data policy for M2. | #27, #28, #11, #12 |

**Sequencing:** A -> B -> C -> D is the critical path, but tests, deployment preparation and independent privacy review may progress concurrently. Do not delay core volunteer workflows for speculative infrastructure refactoring or agent orchestration. Continue focused PRs (at most 5,000 changed lines, applicable CI and Ponytail review) and owner-authorized merges without per-PR checkpoints; checkpoint files only mark actual execution boundaries.

**M1 acceptance:** [ ] repeatable install and deployment recorded; [ ] real OIDC login/secure bootstrap; [ ] complete synthetic member/team/grant journey through browser; [ ] DE/EN/FR/IT, denied-role and invalid-input cases; [ ] data persists across application restart; [ ] HTTPS, secrets and health check demonstrated; [ ] no real personal data; [ ] #28 demo evidence and deviations recorded. A local Compose run alone is not a deployed demo.

**M2 acceptance:** [ ] R03/R04 reviewed and signed off for fields, minors and lawful processing; [ ] export/erasure/audit accepted; [ ] authorization/E2E test evidence; [ ] production identity recovery and operator approval; [ ] known hosting cost and accountable operator; [ ] TLS, backups, retention, restore, upgrade/rollback and monitoring verified; [ ] explicit permission to enter real data.

**Scope control:** Accountless administrator entry of members is sufficient for M1; invitation emails and verified member account linking (#20) are optional unless the pilot specifically requires them. Payments, calendars, chat, tournaments, general-purpose multi-agent orchestration and multi-club features remain outside both MVP milestones. No mandatory paid hosting is authorized. Truly free, reliable public hosting is **not assumed**; first measure deployment resource requirements and document free-tier constraints, then obtain an explicit hosting decision. No infrastructure purchase or real-data migration is part of M1.

## Priority rules and sequencing rationale

1. **P0 — required for a safe first working slice:** pilot intent, identity, authorization, backend and browser membership management, plus local authorization and independent-deployment isolation tests. Security guards precede exposed data-modifying endpoints, even when a demo could be built faster without them.
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

**Decision update (2026-11-14):** [ADR-0003](decisions/0003-oidc-session-proposal.md) confirms Keycloak OIDC + Kotlin BFF architecture; [ADR-0004](decisions/0004-member-policy.md) records onboarding, directory visibility and lifecycle direction. R01 pilot requirements are now accepted (#10 closed; PRs #72–#73). The single-club reset PR #79 and PR-size restoration PR #80 are merged. R05 authentication is complete (#14 closed); privacy/minors' contact visibility, club authorization, first-admin implementation and club/member API acceptance remain open. These decisions guide R01–R18 without implying completion.

**Important limitation:** Opt-in OIDC authentication is implemented and tested with synthetic identities; the schema and login are not club authorization. The initial linked-account club-read guard is implemented; fuller authorization (R06), approved first-admin recovery drill (R07), privacy acceptance (R03), and production deployment proof remain required before real member data.

**Current review budget (owner decision, 2026-11-15):** maximum 5,000 added + deleted lines. Prefer smaller complete, independently reviewable PRs when practical; this supersedes the historical 999-line policy and C05 limit.

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
| R12 ✅ complete | **P0** | [#21 — Frontend sign-in and single-club landing](https://github.com/dkoller/OpenClub/issues/21) | Authenticated local-club entry without a club switcher. | R05, R08 |
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
R07/#16 first-admin recovery approval/drill and frontend `/api` proxy-path integration remain
separate release gates. The browser E2E runs directly against the API, not the proxy.


### R01 pilot requirements acceptance (2026-11-15; complete)

R01 [#10](https://github.com/dkoller/OpenClub/issues/10) closed after merged [PR #72](https://github.com/dkoller/OpenClub/pull/72) and [PR #73](https://github.com/dkoller/OpenClub/pull/73). The pilot administrator accepted a synthetic first-release scenario covering member management, team assignment and configurable club-wide/team-specific permissions. The first existing system to replace is Excel; the longer-term product goal is to replace fragmented club applications, not permanently integrate them. Four first-release UI languages are required: German, English, French and Italian.

**Current member data requirements:** first name, last name and date of birth required for everyone; both email and phone required for adults, optional for minors; gender required for players only when configured by that club. Exact age threshold and gender representation remain R03/R04 policy/design work. **Personal data is never shared, copied, synchronized or auto-filled across clubs**, even for the same authenticated identity. Shared authentication must not imply a shared personal directory. Existing [ADR-0002](decisions/0002-identity-and-membership-model.md) schema is not proof that this privacy boundary is implemented; data-model and authorization remediation are required before real member data.

**Tracked follow-up work:** [#74 — club-local personal-data model](https://github.com/dkoller/OpenClub/issues/74), [#75 — teams/assignments](https://github.com/dkoller/OpenClub/issues/75), and [#76 — configurable permission grants](https://github.com/dkoller/OpenClub/issues/76).

**Release scope changes requiring planning:** team assignments and granular permission grants are newly accepted first-release requirements and require dedicated contracts, implementation and tests; R09/R10 alone do not cover them. R03 privacy, R04 contracts, R06 authorization, R07 admin bootstrap, R15 isolation tests and R19 functional pilot demo are still open. Excel column inventory, imports and migration specifications remain deliberately deferred. R01 acceptance is **requirements acceptance only**, not runtime validation or legal/privacy approval.


### Single-club deployment decision (accepted ADR-0005; implemented foundation in merged PR #79)

**One reusable OpenClub installation serves exactly one club, with its own PostgreSQL database and configuration.** A fresh installation provisions a single club through controlled first-admin setup. There is no multi-club tenant selection, in-app club switcher, global member directory or shared OpenClub login across installations. Existing OIDC authentication was preserved while the never-live database schema was replaced by fresh V1; only intentionally disposable development volumes may be reset manually. Historical multi-club requirements elsewhere in this roadmap are **superseded for the first release**, even when retained to explain earlier work. R01/#10 stays closed for requirements discovery; the architecture revision is tracked by #74.

**Scope changes:** R07/#16 becomes one-time initial club/administrator setup; R08/#17 one-club settings/profile APIs rather than club creation/listing; R12/#21 sign-in and local landing without club switcher; R06/#15 local capability/team authorization without multi-tenant routing. R15/#24 must test singleton-club invariants, installation/database separation and privilege boundaries, rather than rely only on multi-club tests inside one database. R19/#28 must test the same accepted member/team/permission workflows within one club. #75 and #76 remain first-release requirements. R18/#27 must cover independent per-club hosting, costs, backups, upgrades and recovery.

**Gate:** The owner authorized a fresh pre-production database reset: old Flyway V1–V4 history is deliberately replaced by new V1; existing volumes must fail without automated data loss. Before live use, validate accountless member creation, verified linking, first-admin authorization, database separation and preservation of login/session behavior. R03 privacy and the unapproved R04 OpenAPI contract remain blockers to handling real member data. See accepted [ADR-0005](decisions/0005-single-club-installation.md) and [#74](https://github.com/dkoller/OpenClub/issues/74).


**Implementation status after merged PR #79 and #80:** The fresh database schema enforces one club row and local accounts/members. A guarded `GET /api/v1/club` (401/403/200) and four-language sign-in landing are present; other R08/R12 UI and R06 capability work are **not yet complete**. Independent-deployment runbook: [single-club operations](deployment/single-club.md). CI and production acceptance remain separate from the architecture decision; PR-size enforcement was restored in #80.

## R12 browser landing closeout (2026-11-15; merged)

[R12/#21](https://github.com/dkoller/OpenClub/issues/21) is closed after
[PR #169](https://github.com/dkoller/OpenClub/pull/169) exercised the actual
React/Vite proxy and Keycloak Chromium journey (including language persistence,
login, unlinked-account denial and logout), and
[PR #170](https://github.com/dkoller/OpenClub/pull/170) verified the restricted
one-time operator bootstrap enables the previously unlinked account's
single-club landing. Both passed their required CI. This verifies the
**pre-production frontend auth journey**, not a complete member UI or
production nginx/TLS/OIDC recovery sign-off. R13/R14 and R03 still block
live pilot use.

## R18 recovery rehearsal evidence (2026-11-15; partial)

[PR #166](https://github.com/dkoller/OpenClub/pull/166) ran a synthetic
PostgreSQL dump/restore check in GitHub Actions with club, member, team,
assignment, grant and Flyway-history assertions;
[PR #171](https://github.com/dkoller/OpenClub/pull/171) verified current
Kotlin API startup against that restored database. These checks passed.
R18/#27 stays open: real operator restore drills, cost estimates, encryption,
off-site retention, secrets/TLS, rollback, operational sign-off and privacy
approvals have **not** been demonstrated. The CI drill uses only disposable
synthetic data and must not be used as a production recovery claim.
