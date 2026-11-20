# R04 — OpenAPI contract review and approval gate

## Current contract status — 2026-11-20 (frozen 1.19.0)

**Frozen:** [`openapi-v1.yaml`](openapi-v1.yaml) is the owner-accepted **OpenAPI 1.x** contract for the synthetic M1 HTTP surface ([ADR-0007](../decisions/0007-owner-decisions-2026-11-17.md), [#13](https://github.com/dkoller/OpenClub/issues/13)). 1.0.0 froze 21 operations; **1.1.0** adds the per-club adult-age setting (#17); **1.2.0** adds the invitation, revoke, accept and unlink operations (ADR-0008, decisions 15 and 16); **1.3.0** adds the restricted member detail read and the erasure of an archived member (ADR-0008 addendum 4); **1.4.0** adds full member replacement (`PUT`) and reactivation (ADR-0008 addendum 5); **1.5.0** adds the archived-member search for editors (ADR-0008 addendum 7); **1.6.0** adds the six guardian operations (ADR-0008 addendum 8); **1.7.0** adds the member export for editors (addendum 10); **1.8.0** makes the member replacement conditional on the version from the editor's detail read (`ETag`/`If-Match`, 412/428; addendum 11); **1.9.0** adds My profile, a member's own read and contact change (addendum 12); **1.10.0** adds trainings and events (addendum 13); **1.11.0** adds attendance (addendum 14); **1.12.0** adds membership fees and the `MANAGE_FEES` capability (addendum 15); **1.13.0** adds the member list as CSV (addendum 17); **1.14.0** adds announcements and the `POST_ANNOUNCEMENTS` capability (addendum 18); **1.14.1** adds attendance answers to the member export and keeps unknown IDs at 403 for team-scoped holders (second security review); **1.15.0** adds the public member list opt-out, the member's own and the editor's switch (controller decision C5, addendum 19); **1.16.0** adds volunteer shifts (addendum 20); **1.17.0** adds calendar feeds, including the one session-free feed read whose secret path is its credential (addendum 21); **1.18.0** adds attendance statistics per team (addendum 22); **1.19.0** adds email notification settings (addendum 23). It contains exactly the **77 registered** club, member, team, role, grant and public-name operations. `dev/verify-m1-openapi.py` (run by `.github/workflows/m1-contract.yml`) fails if:

- an implemented Ktor route is undocumented, or a documented operation is not implemented;
- the version changes without a new approval;
- a private operation loses its session, 401/403 or CSRF contract.

Selected PostgreSQL/Ktor tests additionally assert privacy-minimized member and public response keys and the safe error envelope.

The M2 draft was extended on 2026-11-18 with six proposed operations (restricted detail read, erase, invitation create, revoke and accept, and account unlink), all marked NOT IMPLEMENTED and approved by the owner on 2026-11-18 (ADR-0008 addendum 4; the four invitation and unlink operations joined the frozen surface as 1.2.0 and the detail read and erase as 1.3.0; replacement and reactivation followed as 1.4.0 on 2026-11-19, ADR-0008 addendum 5, so the draft currently proposes nothing); `dev/verify-m1-openapi.py` pins the exact set, the marker, the CSRF header and the 401/503 contract of every draft operation.

[M1 operation-to-test coverage](m1-route-test-coverage.md) maps each of the 77 operations to the HTTP, input, escalation, revocation and concurrency tests that exercise it and lists the known evidence gaps (route-level 503, runtime schema validation).

**Separate M2 proposals:** [`openapi-m2-draft.yaml`](openapi-m2-draft.yaml) (`0.0.0-draft`; **approved by the owner as a draft on 2026-11-18**, [ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md), which does not authorize real data or any implementation) holds the operations that are **NOT IMPLEMENTED**: full confidential member replacement (`PUT /v1/members/{memberId}`) and HTTP reactivation. Internal database reactivation is not a user-facing route. These need the M2 privacy decisions, audit (#26) and security review. ADR-0007 already fixes that contacts are readable only by explicitly granted roles and that the adult age is configurable per club.

**What 1.x does not mean:** approval of real member data, private-field reads or writes, export or erasure, or complete runtime payload conformance. A change to the M1 surface needs a new, owner-approved contract version. A real-data deployment also requires [M2 authorization](../roadmap.md#delivery-gates).

## Historical review chronology — superseded implementation snapshots

The following dated notes, proposal checklists and multi-club findings are retained to explain earlier decisions. In particular, statements below that member/team/grant routes were not registered, or that multiple clubs share an installation, **must not** be used as current requirements. Only the current-status section above, the accepted ADRs, the frozen `openapi-v1.yaml` and open issues determine unfinished work; `openapi-m2-draft.yaml` is an unapproved proposal, not an authority.

**Status (2026-11-16, through merged PR #268):** The owner-approved minimum synthetic M1 member contract is implemented for authorized list/create/minimal detail/name-only PATCH/archive. Role, team and grant **HTTP/API and DE/EN/FR/IT React operations are also implemented and exercised in CI**, but their full working-draft contract has **not** received final R04 approval. Private-field replacement remains proposed, and real-data processing/M2 approvals are outstanding. See [draft contract](https://github.com/dkoller/OpenClub/blob/f2fede6/docs/contracts/openapi-draft.yaml), [R04 #13](https://github.com/dkoller/OpenClub/issues/13) and [finding #37](https://github.com/dkoller/OpenClub/issues/37).

**Implementation reconciliation (2026-11-16, through PR #274):** The minimum
synthetic M1 club/member/team/grant/role HTTP and DE/EN/FR/IT browser workflows
are registered and exercised in CI, including same-session PostgreSQL
`EDIT_MEMBERS` and `VIEW_MEMBER_DIRECTORY` revocation (#271–#272, #274).
PR #273 aligned empty member-search filters, malformed member-ID 400 and
name-PATCH response codes with real Ktor behavior. These tests do **not**
approve real data, confidential-field replacement, final API versioning,
an independent security audit or operator-hosted M1 HTTPS acceptance.
The dated proposal checklist below remains **historical** wherever it says
member, team or grant endpoints do not exist. #12/#13 and M2 sign-offs
remain open.

**Current review scope:** [`openapi-draft.yaml`](https://github.com/dkoller/OpenClub/blob/f2fede6/docs/contracts/openapi-draft.yaml) was the single-club working draft after PR #79; the historical findings below refer to the retired multi-club proposal, not current endpoints. Authenticated member name list/create/detail/PATCH/archive and the anonymous public-name directory are now implemented; R04 remains open for other draft operations and full acceptance.

This review was written before pilot acceptance and R05 completion. The historical findings below are preserved; **R01/#10 is now closed** and OIDC/Ktor BFF architecture is selected/implemented. The draft contract remains **unapproved**, especially for member data ownership, field requirements and scoped permissions.

**Issue-state reconciliation (2026-11-16):** Owner has now accepted the narrow synthetic M1 contract (#13) and anonymous display-name policy (#12). The full member/team/grant API contract, confidentiality rules for full private editing, version promotion, implementation/security review, and M2 real-data legal/privacy approval remain open. The anonymous public display-name route **does exist** for the owner-approved synthetic M1 scope; it exposes only full adult names or first name plus surname initial for minors, not IDs or private fields. R07/#16 engineering is complete for restricted operator commands; human-approved recovery operations remain open under #180. Existing routes are authentication/session and ID-only `GET /api/v1/club`; member, team and grant routes remain unregistered.

**Verification note:** Latest negative-case/browser changes (#259–#268) test request rejection and UI failure handling but do not expand the agreed field contract or constitute version promotion. Reconcile actual registered routes and their schemas before calling R04 complete; refer to [issue #13](https://github.com/dkoller/OpenClub/issues/13) for outstanding explicit decisions. Earlier “not registered” descriptions below are historical where superseded by the merged implementation.

## Historical single-club contract proposal (superseded where implemented)

The [working YAML](https://github.com/dkoller/OpenClub/blob/f2fede6/docs/contracts/openapi-draft.yaml) now makes the first member API slice reviewable **without authorizing implementation**:

- **Runtime reality:** only `GET /api/v1/club` (plus standalone auth routes) exists; member, team, and grant paths remain proposals. Operator-only bootstrap/recovery from PRs #82/#83 are **not HTTP endpoints**.
- **Two noninterchangeable read boundaries (owner decision, 2026-11-16):** anonymous `/v1/public/members` returns only `displayName`: full first/last name for adults (`Peter Smith`), first name and surname initial for minors (`Peter S.`). Do not include member UUID, DOB, exact age, contacts, gender or roles. Authenticated `GET /v1/members` and detail retain full names/IDs **only with explicit `VIEW_MEMBER_DIRECTORY`**, not merely login; sensitive fields need further access review. Public endpoint implementation must not leak masked surnames through filters, ordering or cursors. No member HTTP routes exist yet. This is synthetic M1 product scope, not M2 legal approval.
- **Pagination:** name-only case-insensitive search (max 100 characters), limit 1–50 (default 20), stable last-name/first-name/ID ordering, and an opaque cursor tied to permission and filter. Exact signed/encrypted cursor implementation and invalidation on authorization changes need review under #18/#24; a stale or invalid cursor must fail closed, not leak records.
- **Security:** session-linked membership is necessary but not sufficient for future writes. Proposed `POST /v1/members` additionally requires explicit member-write capability (to be designed in #76), trusted Origin, session-bound CSRF token, valid input, and no email-based account adoption. Interim ADMIN-only permission in PR #84 is **not final delegation acceptance**.
- **Errors:** 401 without session, 403 for insufficient permission, 404 for a non-existent or non-visible member resource, 400 for validation or cursor errors, 409 for write conflict, and 503 for storage outage. All errors must avoid private field echoes and differ only where the caller is authorized to know an ID exists.
- **Field policy:** every member has name + DOB; both email and phone for adults (age threshold provisionally 18, configurable per club); player gender required only if enabled, with no binary-only assumption. R03 must approve policy, privacy and field-level access.

**Privacy decision tracker:** The updated [R03 single-club inventory and approval matrix](../discovery/privacy-inventory.md) lists real V1 data categories and explicitly unresolved access, retention and legal decisions. This is not sign-off for public member endpoints.

**Unresolved approval gates:** R03 lawful basis, minors' visibility/guardian relationships, exact permission matrix (#76), detailed member update/archive and confidential member-detail contract (#18), role changes/last-admin (#19), teams (#75), and operator review of error/pagination stability. Keep `0.0.0-draft` and do not close #13 or implement these routes until those boundaries are reviewed. YAML validation is only syntax/schema validation, **not** owner/privacy/security acceptance.

## Historical multi-club draft review (superseded)

- Uses a server-enforced session security scheme rather than treating club IDs or roles as authentication.
- Models club-specific membership URLs and explicit `ADMIN` / `MEMBER` roles.
- Defines basic input schemas, bounded page size, and recognizable 401/403/404/409 outcomes.
- Separates person and membership references, matching [ADR-0002](../decisions/0002-identity-and-membership-model.md).

## Historical findings from retired multi-club proposal

| Priority | Finding | Proposed resolution / prerequisite |
| --- | --- | --- |
| P0 | The proposed `POST /v1/clubs` still leaves first-admin authorization and recovery undefined. | Approve secure bootstrap design in [R07 #16](https://github.com/dkoller/OpenClub/issues/16) before exposing the endpoint. |
| P0 | Cookie-authenticated write endpoints need explicit CSRF handling. | Decide BFF session and CSRF token/header mechanism in [R02 #11](https://github.com/dkoller/OpenClub/issues/11); document it in OpenAPI before implementation. |
| P0 | `AddMember` allows an administrator to choose only display name, while the pilot may require invitation-first onboarding. | Confirm workflow and minimum fields under [R01 #10](https://github.com/dkoller/OpenClub/issues/10); do not add personal fields by guesswork. |
| P0 | List operations must never reveal another club's memberships or person IDs, including through searching or pagination. | Define visibility for ADMIN vs MEMBER; test club boundary on every read and mutation under [R06 #15](https://github.com/dkoller/OpenClub/issues/15). |
| P1 | Tenant-hiding behavior is not standardized (403 vs 404); conflicts and validation errors need stable codes. | Document rules and example error JSON for all endpoints, including nondisclosing 404 where appropriate. |
| P1 | No profile/current-session endpoint is defined for the frontend to discover accessible clubs and authentication state. | Agree small same-origin session contract with R02, then add only the required operation(s). |
| P1 | Cursor semantics, sorting, filtered result stability and maximum search lengths are not specified. | Specify deterministic ordering, opaque cursors and safe server-side limits; cover boundary cases. |
| P1 | No automated contract check protects the YAML shape as it evolves. | Add a small OpenAPI 3.1 validation step once contract changes are ready; avoid bringing in a large build framework solely for linting. |

## Historical proposed approval sequence

1. R01: pilot confirms member creation versus invitation, necessary data and access roles.
2. R02: accept identity provider and browser session architecture, including CSRF and logout.
3. R03: review privacy scope and records lifecycle before live data.
4. R04: update draft with approved behavior, add validation, and promote it to an accepted versioned contract.
5. R05/R06/R07: implement authentication, club-scoped authorization and protected bootstrap **before** exposing the member API.
6. R08–R10: implement club/membership paths and verify against the approved contract.

## Proposed member-edit contract gap (2026-11-16; review only)

The internal `MemberDetailsUpdate.update` already performs an authorized **full replacement** of editable fields for active members. The draft now includes proposed `PUT /v1/members/{memberId}` with explicit live `EDIT_MEMBERS`, session/Origin/CSRF checks, and 204/400/401/403/404/409/503 responses. **No HTTP endpoint is implemented or approved.**

**Critical unresolved #12/#13 decision:** the current proposed `GET /v1/members/{memberId}` only returns the member's name and ID. An edit UI cannot safely populate a full replacement from that minimal response: absent optional contact or gender fields would be cleared. Before approving the route, agree which authorized editor can view each DOB/contact/gender field (especially for minors) and whether the UI needs a restricted edit-detail response or a separately reviewed partial-update contract. Never infer fields from a directory listing, echo unapproved PII, or treat synthetic data as legal privacy approval. Keep `0.0.0-draft` until review is complete.

## Historical acceptance checklist (superseded where implemented)

- [ ] Paths, auth/session/CSRF and privilege rules finalized.
- [ ] Errors and nondisclosure rules standardized.
- [ ] Incorporate accepted R01 fields (mandatory DOB, adult email + phone, club-configured player gender), accountless member creation and later invitations into a reviewed replacement club/member contract; the draft still has outdated shapes.
- [ ] Testable examples for cross-club and last-admin rejection.
- [ ] Validate the **approved replacement club/member contract** and cover it with contract tests; YAML syntax checks already exist in CI, but do not imply semantic acceptance.
- [ ] Version status changed from `0.0.0-draft` only after the above are approved.

This review introduces no runtime endpoint or new dependency. The draft remains non-binding.

## Decisions received on 2026-11-14

[ADR-0003](../decisions/0003-oidc-session-proposal.md) selects self-hosted Keycloak as OIDC reference and Ktor BFF sessions. [ADR-0004](../decisions/0004-member-policy.md) confirms manual accountless member creation first; required given/family name; optional contact fields; authenticated same-club directory reads for ADMIN/MEMBER, and ADMIN-only writes. The OpenAPI **draft** reflects these choices but remains unapproved pending session/CSRF design, first-admin issuance, pilot validation and privacy review (especially youth contact visibility). The current database schema must be migrated before implementing these new fields.


## R01 reconciliation (2026-11-15; R04 remains open)

The original R01 dependency and R02 session-architecture uncertainty in the table above are historical. Requirements accepted by the pilot administrator in merged PRs #72–#73 require:

- First/last name and **club-local date of birth mandatory for every member**; both email and phone mandatory for adults and optional for minors; age-of-adulthood threshold still to decide.
- Gender optional except when a club explicitly requires it for players; do not assume binary-only representation.
- **Never share, auto-fill, query or sync another club's personal data**, including for one authentication identity. Global `personId` references/DTOs in the current draft are not proof that this boundary is safe. See [#74](https://github.com/dkoller/OpenClub/issues/74).
- First-release team assignments ([#75](https://github.com/dkoller/OpenClub/issues/75)) and configurable club/team-scoped capability grants with restricted delegation ([#76](https://github.com/dkoller/OpenClub/issues/76)). Four supported UI languages.

The [OpenAPI discussion draft](https://github.com/dkoller/OpenClub/blob/f2fede6/docs/contracts/openapi-draft.yaml) has been reworked for single-club paths, but remains **unapproved**; proposed member/team/grant shapes and interim roles are not authorization approval. **Do not implement the unapproved paths as written.** Revise contract, pagination, field-level access, tenant-safe 401/403/404 behavior and first-admin bootstrap semantics under [R04/#13](https://github.com/dkoller/OpenClub/issues/13) after coordinating with R03 privacy and R06 authorization. Auth-only contract validation in CI does not imply acceptance of the club/member shapes.


## Single-club contract pivot (2026-11-15)

Owner selected one club per installation after R01 closure. R04 must **replace** legacy `/v1/clubs/{clubId}` tenant-selection and list/create-many-clubs assumptions with installation-local member, team, grant and club-settings operations. A controlled one-time setup is separate from normal member APIs. Existing historical contract draft remains explicitly **not implementation-ready**. The fresh V1 single-club schema has no member `club_id` tenant selector; no legacy Flyway migration path is provided because the project was never live. See accepted [ADR-0005](../decisions/0005-single-club-installation.md), #74, R06 and R07.

## Member archival and team-roster proposals (2026-11-15; not approved)

The working draft now includes **proposed only** `POST /v1/members/{memberId}/archive`,
`POST /v1/members/{memberId}/reactivate`, and
`GET /v1/teams/{teamId}/members` operations. These are documentation of
implementation gaps, not registered routes:

- Archive requires an explicit `EDIT_MEMBERS` actor, an unlinked ordinary
  member, Origin and session CSRF, and an approved retention/audit procedure.
  Archived personal data remains in the database and backups; archive is not
  erasure or an export/deletion solution.
- Reactivation must **never** silently reinstate OIDC links, team assignments,
  role privileges or old capability grants. Current adult-contact and player
  policy continues to apply. Review conflict, unauthorised and already-active
  behavior before implementation.
- The team roster requires BOTH `VIEW_MEMBER_DIRECTORY` and the exact team's
  viewing/managing grant (or equivalent club-wide team grant). Only the local
  member UUID and first/last names may be returned; not birth dates, gender,
  contacts or account identities. Even minors' **names** require R03 review.
- Future opaque cursors must protect integrity and bind scope, filter and
  actor permissions. The existing internal typed `After` key is **not**
  suitable for direct HTTP exposure; wrong-team and missing-resource errors
  must not leak member existence.

The current PostgreSQL services already enforce parts of these rules internally
and have synthetic tests, but public APIs, localized browser workflows, audit,
retention, data-subject rights and legal/privacy sign-off are missing. GitHub
OpenAPI syntax validation cannot establish authorization or privacy approval.
Keep the entire contract at `0.0.0-draft` until independently reviewed.

## M1 member HTTP/browser implementation (2026-11-16)

After the owner's minimum technical #12/#13 decisions, the secured `GET/POST /api/v1/members`, `GET/PATCH /api/v1/members/{memberId}` and `POST /api/v1/members/{memberId}/archive` routes are implemented using verified sessions and current database-backed `VIEW_MEMBER_DIRECTORY` / `EDIT_MEMBERS` grants. All mutations require exact configured Origin and session CSRF; inputs are capped at 4 KiB and parsed as JSON objects. Name editing does not round-trip DOB, contacts or gender. Per-process AES-GCM cursors are bound to the actor and name filter; a server restart invalidates old cursors (400). The restricted operator bootstrap explicitly seeds the initial administrator's directory and member-edit grants, but no other ADMIN automatically inherits these rights. The four-language browser UI supports synthetic search/create/edit/archive with loading, denial and error handling. See backend tests and real Keycloak Playwright flow.

OpenAPI remains `0.0.0-draft` until the full scoped contract is independently reviewed; confidential-field replacement/reads, role/team/grant delegation, archival retention approval and any real-data handling are **not** authorized by this implementation.

## Synthetic team/grant contract reconciliation (2026-11-16, PR #212)

The working OpenAPI draft now matches the registered M1 paths `/v1/teams`, `/v1/teams/{teamId}`, `/v1/teams/{teamId}/members`, `/v1/teams/{teamId}/members/{memberId}`, and `/v1/grants`, including the `{"items":[...]}` team/grant envelopes, exact-team roster cursor, name-confirmed deletion, and strict CSRF/Origin grant mutation. The obsolete proposed `/v1/permission-grants` path and claims that team/grant HTTP is absent were corrected. This is an implementation-to-draft inventory, **not** final R04 approval, a backward-compatibility promise, or permission to handle real member data. Private-field read/update, retention, audit and M2 production policies require their own decisions.

## Synthetic M1 permission-member search freshness

The permissions editor cancels superseded member-name searches and associated grant lookups. An outdated response cannot overwrite a newer selected member or its visible grants; stale options clear as soon as a new query is submitted, and canceled requests do not display misleading errors. The disposable Keycloak/Chromium journey delays a fictional old search and verifies the latest member is selectable before normal grant/revoke. Server-side grant authorization and M2 privacy gates are unchanged.

## Synthetic permission member-switch read isolation

Switching the selected member in the permissions editor clears the previously shown grants immediately and aborts an older in-flight grant read, so stale grant rows cannot appear under another member's name. Search/selection are disabled while a grant mutation is busy. Disposable Keycloak/Chromium acceptance exercises two fictional members and verifies a deliberately delayed old grant response is canceled. PostgreSQL remains the authority for all grants; this UI defense does not replace security review or M2 approval.

## Transient permission-editor startup recovery (synthetic M1)

If the initial role/grant capability probe returns a transient server failure, the permission editor shows a localized non-sensitive error and a DE/EN/FR/IT retry instead of permanently disappearing until a page reload. A successful subsequent authorization check restores the existing editor; a genuine 403 still hides it. The real Keycloak browser acceptance also injects a genuine 403 on the capability probe and verifies that the permission editor disappears without presenting a retry bypass. The disposable Keycloak/Chromium acceptance injects a failed startup read and verifies successful retry without weakening server-side authorization or M2 privacy gates.
