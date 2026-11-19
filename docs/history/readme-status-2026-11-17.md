> Archived README status paragraphs captured on 2026-11-17, when the README was shortened to current status only.
> This is historical evidence, **not active delivery guidance**. For current scope and acceptance, use [the roadmap](../roadmap.md) and [open issues](https://github.com/dkoller/OpenClub/issues). Text is unchanged from `main` `cf8974e`; relative links were rewritten for this folder.

# OpenClub README status history

**Current status (2026-11-17):** OpenClub implements synthetic single-club member, team, role, grant and settings workflows with a four-language React UI. Keycloak and Rauthy are both supported as alternative OIDC providers; CI includes real browser/OIDC tests, permission-revocation checks and disposable Compose restart evidence. **M1 remains unaccepted** until an operator-hosted HTTPS/OIDC synthetic demo, persistence and actual resource/cost evidence are witnessed; **M2 remains blocked** on private-data, privacy/legal, recovery and release approval. Consult the [current roadmap](../roadmap.md), [M1 evidence checklist](../deployment/m1-resource-evidence.md) and [open issues](https://github.com/dkoller/OpenClub/issues) rather than treating individual PR histories as release gates.

**Implemented application status (reviewed 2026-11-17):** OpenClub is a **single-club, synthetic-data-only pre-production application**. Local PostgreSQL, verified Keycloak/OIDC sessions, operator-only initial administrator bootstrap/recovery, and member/team/grant backend APIs and DE/EN/FR/IT React administration are implemented. An authorized administrator can create/search/edit/archive fictional members, create/rename/delete eligible teams, assign/unassign members, and delegate/revoke club- and team-scoped grants; all use explicit capability enforcement and current-session CSRF/Origin checks. Public active-member names show adults' full names and minors' first name plus surname initial, without private fields. The authorized club landing now displays its own name ([PR #207](https://github.com/dkoller/OpenClub/pull/207)). Disposable Keycloak/Chromium acceptance covers the main member/team/grant workflows and all four languages, including archive cancellation/confirmation and member-specific accessible prompts ([PRs #206, #231–#232](https://github.com/dkoller/OpenClub/pull/232)). Current authorization tests reject revoked verified identities for sessions, member/team operations, member directory and club metadata ([PRs #227–#230 and #233](https://github.com/dkoller/OpenClub/pull/233)); broader negative/accessibility and operational acceptance remain open.

**Historical browser-hardening checkpoint (PR #268, 2026-11-16; superseded by the current engineering and authentication summaries above):** [PR #268](https://github.com/dkoller/OpenClub/pull/268) adds expired-session role and permission browser regressions. Across [PRs #256–#268](https://github.com/dkoller/OpenClub/pull/268), synthetic CI now covers translated policy/member errors, permission bootstrap retry, fail-closed 401/403 private editors and canceled stale team roster reads. This is targeted Chromium/Keycloak and injected-denial evidence, **not** exhaustive accessibility/security certification or a deployed demonstration. The currently unfulfilled M1 gate is an operator-approved external HTTPS/OIDC demo with recorded synthetic-browser, persistence/restart and cost evidence; M2 real-data use remains separately prohibited pending legal/privacy and operational sign-off.

**Not yet approved or shipped:** A publicly deployed TLS/OIDC demonstration (only an optional operator-supplied HTTPS reference overlay and read-only probe exist), full adult-age/private-field settings and complete approved API contracts, real-data privacy/legal sign-off, deletion/export/audit lifecycle, production backups and witnessed recovery. Synthetic role-management workflows exist but have no M2 production approval. M1 (synthetic deployed demo) and M2 (real-club pilot) remain **unaccepted**. GitHub issues and [the milestone gates](../roadmap.md#delivery-gates) track the remaining work.

## Deployment scope decision (2026-11-15)

OpenClub targets **one club per installation**, with an independently operated database and configuration; the same open-source code can serve different clubs via separate deployments. The fresh single-club schema and basic authenticated club lookup are implemented in [PR #79](https://github.com/dkoller/OpenClub/pull/79) under [accepted ADR-0005](../decisions/0005-single-club-installation.md). Old development database volumes are **not migrated**: reset only deliberately disposable pre-production data, as documented in the [deployment guide](../deployment/single-club.md). This is **not** production/privacy approval.

**Role-management increment (#211, synthetic M1):** Privileged administrators with a verified identity and explicit MANAGE_PERMISSIONS grant can read an active member's role and promote/demote through the DE/EN/FR/IT browser. Changes use the serialized last-admin-safe backend service, with strict Origin/CSRF checks, negative HTTP tests and Keycloak browser acceptance. This does not approve role management for real member data or bypass M2 audit/privacy gates.

**Player creation (synthetic M1, PR #213):** The authorized member-create form now supports a player checkbox and an optional free-text gender field (required in the form only when the installation's existing `gender_required_for_players` policy is enabled). The linked-member club read exposes that policy boolean, not any person's gender; member directory and public responses still contain names only. Real gender values, options, consent, retention and visibility remain R03/R04/M2 decisions.

**Synthetic player policy administration (PR #214):** A verified identity-bound administrator with explicit MANAGE_PERMISSIONS can toggle the existing player-gender requirement from the four-language club settings form. PostgreSQL prevents enabling a stricter rule when existing players lack the required field. No personal gender data is returned by the settings API; real-data policy and consent remain R03/R04/M2 gates.

---

## README status block of 2026-11-17 (replaced on 2026-11-19)

_Kept verbatim for history; the README now carries the 2026-11-19 status._

### Current status (2026-11-17)

OpenClub is a **pre-production, synthetic-data-only** application. Each installation serves **one club** with its own PostgreSQL database ([ADR-0005](../decisions/0005-single-club-installation.md)).

**Works today**, with test (fictional) data only:

- An authorized administrator can search, create, edit and archive members, manage teams and team assignments, change member roles (with last-administrator protection), grant and revoke club- or team-scoped permissions, and edit the club name and player-gender policy.
- The UI is available in German, English, French and Italian. Public member lists show adults' full names and minors' first name plus surname initial, and no private fields.
- Login uses OIDC through a backend-for-frontend with server-side sessions. Keycloak and Rauthy are both supported, with one issuer per installation ([ADR-0006](../decisions/0006-oidc-provider-choice.md)). The first administrator is created by an operator-only bootstrap.
- Every protected read or write checks the actor's current verified identity and explicit grant inside the same SQL statement. CI runs PostgreSQL integration tests, real Keycloak/Rauthy browser tests and disposable Compose restart checks.

**Not yet accepted:**

- **M1, hosted synthetic demo:** needs an operator-run, externally reachable HTTPS/OIDC installation, with witnessed browser, persistence and resource/cost evidence ([#27](https://github.com/dkoller/OpenClub/issues/27), [#28](https://github.com/dkoller/OpenClub/issues/28)). The repository ships only a [reference HTTPS overlay](../deployment/single-club.md#opt-in-public-https-reference-deployment-synthetic-data-only) and probes.
- **M2, real member data:** blocked on privacy/legal approval, the private-field contract, export/erasure/audit, backups and a witnessed recovery drill ([#12](https://github.com/dkoller/OpenClub/issues/12), [#13](https://github.com/dkoller/OpenClub/issues/13), [#26](https://github.com/dkoller/OpenClub/issues/26), [#180](https://github.com/dkoller/OpenClub/issues/180)). **Do not enter real personal data.**

The [roadmap](../roadmap.md#delivery-gates), the [M1 evidence checklist](../deployment/m1-resource-evidence.md) and the [open issues](https://github.com/dkoller/OpenClub/issues) are authoritative. Earlier dated README status notes are kept in [docs/history](../history/readme-status-2026-11-17.md).
