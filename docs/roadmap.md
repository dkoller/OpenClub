# OpenClub delivery roadmap

**Current acceptance checkpoint (2026-11-17; `main` `331f83e`, through merged #306):** Single-club synthetic member/team/role/grant administration and DE/EN/FR/IT workflows are implemented. Keycloak remains supported; Rauthy has separate real-HTTPS synthetic password, MFA, passkey, key-rotation, restore and lost-passkey browser acceptance (#283–#297). The recovery runbook exists (#298), but cannot supply independent human authorization or a witnessed operator drill (#180). Latest `main` Build/test, Compose smoke and M1 contract checks passed. **M1 remains unaccepted** until an operator-approved externally reachable HTTPS/OIDC installation completes a witnessed synthetic browser journey, hosted restart/persistence proof and host/provider resource/cost acceptance (#27/#28). **M2 remains unaccepted** pending privacy/legal, retention, private-field, export/erasure/audit, backup/restore and operator recovery approval (#12/#13/#26/#180). Older dated sections are historical, not current feature blockers.

**New verified synthetic M1 evidence (#301–#306, 2026-11-17):** [#301](https://github.com/dkoller/OpenClub/pull/301) adds issuer-pinned Keycloak/Rauthy external HTTPS discovery probing plus three non-peak container memory samples; [#302](https://github.com/dkoller/OpenClub/pull/302) checks 21 M1 Ktor routes against the OpenAPI draft and tests same-session grant revocation; [#303](https://github.com/dkoller/OpenClub/pull/303) verifies keyboard-operated DE/EN/FR/IT member search and archive cancellation in Keycloak/Chromium; [#304](https://github.com/dkoller/OpenClub/pull/304) checks verified identity revocation after restoring a role grant; [#305](https://github.com/dkoller/OpenClub/pull/305) proves a fictional club UUID persists across disposable PostgreSQL/API/web restarts; [#306](https://github.com/dkoller/OpenClub/pull/306) checks minimal Ktor member/public response JSON and aligns the shared error schema. None of these prove a 4 GB complete host footprint or external M1 sign-off; see [M1 operations evidence](deployment/m1-resource-evidence.md).

**Previous milestone review (historical):** 2026-11-16 (through merged PR #274) · **Status:** Single-club schema, operator bootstrap/recovery, verified sessions, synthetic member/team/assignment/grant and role browser workflows, club-name and player-gender settings and Keycloak acceptance; full field/settings contract, deployed TLS demo, privacy sign-off and production release remain open · **Source of truth for work:** [GitHub issues](https://github.com/dkoller/OpenClub/issues)

This roadmap inventories the work identified in the [original first-milestone plan](concepts/ai/first-milestone.md), [MVP proposal](concepts/product/mvp.md), accepted [stack ADR](decisions/0001-stack-selection.md), and [current single-club ADR-0005](decisions/0005-single-club-installation.md) (historical [ADR-0002](decisions/0002-identity-and-membership-model.md) is superseded). The numbered **R01–R22** items are priority/dependency order, **not dates or commitments**. Each implementation PR uses at most **5,000 added + deleted lines** and requires applicable CI and review; the owner-authorized controlled development loop may merge after checks pass without a separate per-PR owner approval (see root `AGENTS.md`). Production/privacy/security approvals remain separate.

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

**Implementation versus acceptance (after PR #207):** Anonymous privacy-masked public names, authorized member search/create/name-only edit/archive, teams/list/create/rename/delete/rosters/assignments, and scoped grant/revoke have live HTTP and four-language React surfaces. PRs #200/#202/#206 give disposable Keycloak/Chromium evidence for member/team/grant and DE/FR/IT/EN navigation; #205 hardens archived-actor authorization, and #207 displays linked-member-only club name/slug in the landing and contract. Restricted deletion cannot silently destroy populated/delegated teams. This is **synthetic M1 engineering**, not a deployed HTTPS demo, exhaustive denial/invalid-input acceptance or permission for actual member data. Full adult-age policy settings, production role-management sign-off, R04 contract approval, R03 legal/privacy decisions and M2 release gates remain open.

## Historical M1 implementation check (through merged PR #218; 2026-11-16)

The authorized single-club browser can now edit the club name (#210), promote/demote members under explicit MANAGE_PERMISSIONS with last-manager safeguards (#211), create synthetic players with an installation policy-aware gender field (#213), configure the player-gender requirement with PostgreSQL existing-record conflict checks (#214), and require explicit confirmation before archiving a member (#217). The OpenAPI working draft's team/grant paths and response envelopes were reconciled with the registered routes (#212). Disposable real Keycloak/Chromium acceptance covers adult contact enforcement, the configured player requirement and archive cancellation/confirmation (#215, #217). Database-backed member creation and both member-edit paths now reject absent/revoked verified identity associations in the modifying SQL statement (#216, #218).

**M1 still incomplete:** Actual deployed synthetic TLS/OIDC environment and evidence, additional denied-role/edge/accessibility verification and final review of the technical contract are outstanding. **M2 still blocked:** real-data legal/privacy/retention decisions, private detail edit/read scope, export/erasure/audit, operational recovery and independent release approvals. These technical merges do not authorize real member data. The older progress snapshots below remain historical rather than authoritative.

## Historical security, accessibility and deployment preparation (through PR #233; 2026-11-16)

Merged synthetic M1 improvements after #218 cover live identity-bound archival/reactivation (#220), browser-facing security headers and same-origin CSP (#222/#224), a read-only external HTTPS/OIDC acceptance probe (#223), corrected local login guidance (#225), and an optional operator-supplied Caddy TLS/OIDC Compose overlay (#226). None of these provisions a public host or a real-data installation. PostgreSQL-backed team operations and roster/visibility checks reject revoked identity associations (#227), as do private member directory and detail reads (#228), sliding session reads/rotation (#229), authenticated `/auth/me` HTTP use of revoked cookies (#230), and private single-club metadata reads (#233). The real Keycloak browser journey tests localized archive cancellation in all DE/FR/IT/EN languages (#231) and accessible per-member confirmation labels (#232). Earlier security safeguards still apply, including explicit grants, Origin/CSRF, last-manager checks and privacy-masked anonymous display names.

**Still outstanding for M1:** actual **deployed** HTTPS/OIDC demo with an operator-provided free/approved host, DNS/provider credentials, end-to-end synthetic browser evidence, persistence/restart and resource/cost proof; broader negative/assistive-tech coverage and technical-contract review. **Still outstanding for M2:** approved personal-data policy/retention/export/erasure/audit, independent operator-security recovery drill, backup/upgrade evidence and accountable release approval. Do not interpret the new reference overlay or green CI as an actual hosted demo, completed WCAG audit, real-data consent or production sign-off. Older sections below are retained for historical traceability.

## M1 browser reliability and HTTPS validation update (through PR #257; 2026-11-16)

The synthetic browser and API workflow has progressed beyond the earlier #233 snapshot. PRs #235–#236 clarify adult contact requirements across DE/FR/IT/EN and add short-lived HSTS to the optional demo ingress. PRs #237–#245 extend synthetic anonymous-name lifecycle, revoked/unlinked account denial, HTTPS response assurance and race/error handling in member search. PRs #246/#248 tighten team member-search cancellation and reject ambiguous HSTS directives; PRs #250–#254 cover team roster retries, stale permission/role reads and team-directory recovery. PR #256 keeps player-policy conflict feedback correctly localized after a language switch. PR #257 restores the permission editor after a transient initial capability-check failure; its previous conflicting implementation PR #255 was closed, not merged. These changes passed applicable PR-head CI, including disposable Keycloak browser acceptance where required.

**Remaining M1 gates are unchanged:** an actual operator-hosted HTTPS/OIDC synthetic demo with recorded evidence, broader denied-role and assistive-tech testing, technical-contract review and deployment persistence/restart proof. **M2 remains blocked** on independent privacy/legal, data-retention/export/erasure/audit, backup/recovery and accountable production approval. Passing synthetic CI or adding reference TLS configuration does not authorize live member data.

## Historical M1 authorization, error-recovery and browser acceptance (through merged PR #268; 2026-11-16)

After #257, disposable Keycloak/Chromium acceptance added explicit capability-denial behavior (#259), player-policy transient error localization and recovery (#260), and member-directory failed-search messages that follow the selected DE/EN/FR/IT language (#261). Role writes (#262), grant reads/search/writes (#263) and team candidate searches (#264) now close privileged browser controls on HTTP 403; the team roster aborts superseded reads after membership changes (#265). Private member administration (#266), role/grant/team/club-settings interfaces (#267) also fail closed on HTTP 401, with additional role and permission expiry browser acceptance (#268). Issues #24 and #25 track the remaining security and end-to-end assurance gaps. These checks include injected failure responses; they do **not** constitute independent role-revocation penetration testing or a deployed TLS demo.

**Status separation:** The authorized synthetic M1 member/team/grant/role workflows are implemented locally and tested in CI. M1 is **not complete** until an operator-approved external HTTPS/OIDC installation is reachable, its full synthetic browser journey is witnessed, and restart/persistence, resource/cost and limitations are recorded in #27/#28. M2 additionally requires legal/privacy/retention, private-field and lifecycle policy, safe export/erasure/audit, witnessed recovery and explicit release approval (#12/#13/#26/#180). Historical implementation snapshots below are retained as dated history, not current blockers for already registered routes.

## Historical M1 test and contract reconciliation (through merged PR #274; 2026-11-16)

PR #270 clarified the development instructions for coherent vertical slices and
centralized only the repeated authorized member-name response shape. PR #271
added PostgreSQL/Ktor coverage proving that a revoked `EDIT_MEMBERS` grant
denies writes during an otherwise valid session while a separate read grant
continues to work, and that restoring the grant restores writes. PR #272
extended this to a real signed-in disposable Keycloak/Chromium browser, using
live PostgreSQL revocation and UI fail-closed handling. PR #274 demonstrated
the complementary `VIEW_MEMBER_DIRECTORY` revocation with real browser/API
403 denial, hidden private names and retained anonymous display-name access.
PR #273 corrected the synthetic OpenAPI working draft's optional empty member
search, detail-ID 400 and name-update response descriptions against Ktor tests.

**Still open:** The full R04 private-field/versioned contract and R03 lawful
data-processing decisions; broader independently reviewed authorization and
accessibility coverage (#24/#25); and an actual operator-approved, reachable
HTTPS/OIDC synthetic demo with restart, persistence and resource/cost evidence
(#27/#28). M2 additionally requires private lifecycle/export/erasure/audit,
operator recovery approval (#180) and explicit real-data release. None of these
PRs authorizes entering real member information or claims a hosted M1 demo.
Earlier dated progress sections are historical snapshots.

## Code-to-tracker reconciliation (2026-11-16; after PR #201)

**Current verified state (PRs #194–#207):** The member, team and grant browser/API vertical slices are implemented with explicit database-backed capability and verified-session controls, and have passed applicable Build/test, Compose, size and real Keycloak/Chromium CI. Read-only single-club metadata is returned only for linked members and displayed on authenticated landing. Browser acceptance covers member changes, team create/assign/unassign/rename, grant/revoke and localized admin surfaces in DE/EN/FR/IT. PR #204 added guarded team deletion; PR #205 blocked archived actors from team/capability operations. Remaining M1 critical-path work is selected from open issues: further policy/field contract review, broader negative and accessibility verification, and an actual reproducible synthetic TLS/OIDC deployment with acceptance evidence. No real-data privacy approval or deployed-demo proof is inferred.

**Historical baseline (after PR #179, now superseded):**

**Historical snapshot (superseded by the implemented member/team/grant APIs):** At that time Ktor previously registered authentication/session routes and ID-only `GET /api/v1/club`; the anonymous `/api/v1/public/members` display-name route and four-language browser view have now been added with synthetic database/API tests; the internal PostgreSQL member directory, create/update/archive/reactivation, team assignments, grants and role safeguards **are not registered as member/team/grant HTTP routes**. React currently provides sign-in/denial/logout and a DE/EN/FR/IT club landing, not member/team/permission administration. Real Chromium tests exercise OIDC/session/club landing and rotation, not full membership workflows. The stock Compose and nginx setup is localhost HTTP development infrastructure, **not deployed HTTPS**; `/health` and `/api/health` are liveness, not database readiness. Synthetic restore and recovered API startup are tested; a witnessed production recovery remains outstanding.

**Issue state reconciliation (after PR #207):** #75 (teams/assignments) and #76 (scoped grants) now have their main end-user HTTP/React/Keycloak workflows and remain open only for reviewed contract and remaining boundary/acceptance gaps; #25 has real three-workflow and four-language browser coverage but not exhaustive denied-role and invalid-field scenarios. #17/#22 have name and player-gender settings but not adult-age policy changes; #19/#23 have guarded role UI/API but need complete release review; #27/#28 need a hosted TLS synthetic demo and operator evidence. R03 [#12](https://github.com/dkoller/OpenClub/issues/12) and R04 [#13](https://github.com/dkoller/OpenClub/issues/13) remain open for real-data privacy and complete API contract review. R07 #16 is implemented as restricted operator commands; #180 separately tracks M2 acceptance of sole-admin recovery. Neither green CI nor an issue label authorizes real-data entry.

**Active policy:** Maximum 5,000 additions + deletions per PR; controlled development-loop auto-merges only after applicable CI, Ponytail/appropriate review and preserved security/privacy gates. Historic issue paragraphs referring to 1,000 lines, human approval for every PR, global person or club switching are superseded by [AGENTS.md](../AGENTS.md) and ADR-0005, not additional requirements.

## Two delivery milestones and critical path (2026-11-16)

**Execution reliability (2026-11-16):** The controlled loop now specifies actual stop conditions, branch/PR/CI inspection when resuming, diagnosis and bounded retry of CI failures without bypassing required checks, and an evidence-based handoff at genuine execution boundaries. See [AGENTS.md](../AGENTS.md#stop-recovery-ci-failures-and-handoff). These instructions do not create background execution.

**Throughput policy (2026-11-16):** Optimize for complete, tested M1 workflows over PR count. Prefer cohesive vertical slices within 5,000 changed lines; classify implementation/M1/M2 blockers; parallelize only isolated work; measure CI before optimizing; and use incremental reviews after merges. Preserve privacy/security approvals and all applicable CI. See [AGENTS.md](../AGENTS.md#throughput-without-weaker-gates).

**M1 member workflow increment (2026-11-16):** Verified-session name directory, accountless create, name-only update and archive routes are now connected to a DE/EN/FR/IT React administration flow and synthetic negative/Keycloak browser tests. The operator bootstrap seeds explicit read/edit grants for the first ADMIN. Private DOB/contact edit-detail, adult-age settings, final full contract acceptance, and M1 deployment remain open; role management and narrow club settings have synthetic routes. Team/grant HTTP and browser workflows are implemented in PRs #196–#200. No M2 approval is implied.

**Owner product decision (2026-11-16):** The synthetic M1 includes an anonymous public *display-name-only* active-member list: adult `Peter Smith`, minor `Peter S.`. Derive minor status server-side using configured adult threshold; never expose full minor surnames, member IDs, DOB, contacts or grants in public responses. Full-name member administration remains explicitly authorized, separate from the public display endpoint. The owner accepted the minimum #13 technical contract, not full private/team/grant contracts or M2 real-data publication. See [R03 inventory](discovery/privacy-inventory.md) and [OpenAPI working draft](contracts/openapi-draft.yaml).

**Development-loop next-step rule (2026-11-16):** Each increment selects the highest-priority unblocked, reviewable contribution to M1 from the current `main` and issue state. Prioritize minimum approved R03/R04 boundaries, secured HTTP member/team/grant/settings workflows, React administration, synthetic end-to-end verification and deployed-demo evidence. Reuse internal backend services; do not stop because one task needs human privacy or production approval—document the specific blocker and continue with independent safe M1 work. Never infer legal approval. The full selection algorithm and no-pause rule are in [AGENTS.md](../AGENTS.md#deterministic-next-increment-selection-m1-critical-path).

**Milestone M1 — deployed synthetic-data demo (no real member data).** A reachable, reproducibly deployed one-club installation with configured OIDC and TLS, where an authorized administrator can use the actual React UI to create, search, edit, and archive synthetic members, assign them to teams, and delegate/revoke club/team capabilities in DE/EN/FR/IT. Verify full browser/API authorization-denial and validation flows with synthetic fixtures. Record the deployment URL or environment, configuration boundaries, test evidence and known limitations in R19/#28. M1 is **not** privacy approval or authorization to store real names, contact details, or birth dates.

**Milestone M2 — real-club production pilot.** M1 plus approved R03 privacy/legal/retention/minors policy and R04 field/access contract, tested export/erasure/audit paths, independent security and operator-recovery acceptance, documented operator identity and responsibilities, production TLS/secrets/OIDC/monitoring, encrypted off-site backups and a witnessed restore/upgrade/rollback drill. Explicit owner/operator release acceptance is required before any real member data. Neither a green CI badge nor M1 automatically promotes the system to M2.

### MVP workstreams (dependency guide, not sequential PR phases)

| Batch | Concrete completion | Primary tracking |
| --- | --- | --- |
| **A — decisions and secured API** | Resolve minimum R03/R04 decisions before exposing personal fields; deliver authorized club settings, member CRUD/archive, teams/assignments and grant/revoke HTTP routes with CSRF, rate/size and negative authorization tests. | #12, #13, #15, #17–#19, #75, #76 |
| **B — usable browser MVP** | Club settings plus accessible member list/search/create/edit/archive, team assignments, scoped permission management and complete DE/EN/FR/IT coverage. | #22, #23, #75, #76 |
| **C — assurance and lifecycle** | Browser journeys, SQL/API privilege regressions, last-admin safety, safe export/deletion and audit, all with synthetic data. Security reviews remain gates. | #16, #24–#26 |
| **D — deployment and acceptance** | Reproducible no-paid-service-required synthetic environment with TLS/OIDC, health checks, restart/persistence, operational evidence and M1 acceptance; separately validate production costs, backup/restore/upgrade and real-data policy for M2. | #27, #28, #11, #12 |

**Sequencing:** The batches name **workstreams, not mandatory backend-then-frontend-then-test PR phases**. Resolve the minimum #12/#13 technical field/access contract before exposing member data, then prefer complete authorized API + React + synthetic negative/E2E **member workflow slices** using existing services, within the 5,000-line cap. Include security tests with each slice rather than deferring them all to Batch C. Independent privacy review, security tests and deployment preparation may proceed concurrently without overlapping ownership. M2-only recovery sign-off (#180) does not block safe synthetic M1 implementation. Do not delay volunteer workflows for speculative infrastructure or orchestration; use applicable CI/Ponytail review and owner-authorized merges without per-PR checkpoints. See [root loop rules](../AGENTS.md#throughput-without-weaker-gates).

**M1 acceptance:** [ ] *Externally hosted* repeatable install/deployment recorded; [x] real OIDC login/secure bootstrap validated with disposable synthetic fixtures; [x] complete synthetic member/team/grant journey through real Keycloak browser CI (PR #200); [ ] complete hosted DE/EN/FR/IT, denied-role and invalid-input cases (targeted local keyboard/browser checks passed #303); [ ] *hosted* data persists after application restart (disposable PostgreSQL/API/web restart passed #305); [ ] external HTTPS, secrets and health checks demonstrated (local smoke and read-only probe exist); [ ] no real personal data in the deployed demo; [ ] #28 hosted demo evidence and deviations recorded. A local Compose run is not a deployed demo.

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

**Role workflow progress (PR #211, synthetic M1):** Verified administrator-role GET/PATCH, DE/EN/FR/IT browser form and role promotion/demotion are implemented with existing serialized last-admin checks and negative PostgreSQL/API/Keycloak acceptance. Live-person deployment, audited production role changes, full R04 private contract approval and M2 privacy approval remain separate gates. The role service now explicitly rejects archived actors and excludes archived members from administrator eligibility counts.
