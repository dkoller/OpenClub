# R04 — OpenAPI contract review and approval gate

**Status:** Minimum synthetic M1 technical contract approved by owner and implemented for authorized list/create/minimal detail/name-only PATCH/archive; private-field replacement, roles, teams and grants remain draft, and M2 real-data approvals are outstanding. See [draft contract](openapi-draft.yaml), [R04 #13](https://github.com/dkoller/OpenClub/issues/13) and [finding #37](https://github.com/dkoller/OpenClub/issues/37).

**Current review scope:** `openapi-draft.yaml` is the single-club working draft after PR #79; the historical findings below refer to the retired multi-club proposal, not current endpoints. Authenticated member name list/create/detail/PATCH/archive and the anonymous public-name directory are now implemented; R04 remains open for other draft operations and full acceptance.

This review was written before pilot acceptance and R05 completion. The historical findings below are preserved; **R01/#10 is now closed** and OIDC/Ktor BFF architecture is selected/implemented. The draft contract remains **unapproved**, especially for member data ownership, field requirements and scoped permissions.

**Issue-state reconciliation (2026-11-16):** Owner has now accepted the narrow synthetic M1 contract (#13) and anonymous display-name policy (#12). The full member/team/grant API contract, confidentiality rules for full private editing, version promotion, implementation/security review, and M2 real-data legal/privacy approval remain open. This working draft is **not** a claim that any public member HTTP route exists. R07/#16 engineering is complete for restricted operator commands; human-approved recovery operations remain open under #180. Existing routes are authentication/session and ID-only `GET /api/v1/club`; member, team and grant routes remain unregistered.

## Current single-club contract decisions proposed in this PR (#13)

The [working YAML](openapi-draft.yaml) now makes the first member API slice reviewable **without authorizing implementation**:

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

## Historical findings to resolve in the single-club contract

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

## Proposed approval sequence

1. R01: pilot confirms member creation versus invitation, necessary data and access roles.
2. R02: accept identity provider and browser session architecture, including CSRF and logout.
3. R03: review privacy scope and records lifecycle before live data.
4. R04: update draft with approved behavior, add validation, and promote it to an accepted versioned contract.
5. R05/R06/R07: implement authentication, club-scoped authorization and protected bootstrap **before** exposing the member API.
6. R08–R10: implement club/membership paths and verify against the approved contract.

## Proposed member-edit contract gap (2026-11-16; review only)

The internal `MemberDetailsUpdate.update` already performs an authorized **full replacement** of editable fields for active members. The draft now includes proposed `PUT /v1/members/{memberId}` with explicit live `EDIT_MEMBERS`, session/Origin/CSRF checks, and 204/400/401/403/404/409/503 responses. **No HTTP endpoint is implemented or approved.**

**Critical unresolved #12/#13 decision:** the current proposed `GET /v1/members/{memberId}` only returns the member's name and ID. An edit UI cannot safely populate a full replacement from that minimal response: absent optional contact or gender fields would be cleared. Before approving the route, agree which authorized editor can view each DOB/contact/gender field (especially for minors) and whether the UI needs a restricted edit-detail response or a separately reviewed partial-update contract. Never infer fields from a directory listing, echo unapproved PII, or treat synthetic data as legal privacy approval. Keep `0.0.0-draft` until review is complete.

## Acceptance checklist

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

The [OpenAPI discussion draft](openapi-draft.yaml) has been reworked for single-club paths, but remains **unapproved**; proposed member/team/grant shapes and interim roles are not authorization approval. **Do not implement the unapproved paths as written.** Revise contract, pagination, field-level access, tenant-safe 401/403/404 behavior and first-admin bootstrap semantics under [R04/#13](https://github.com/dkoller/OpenClub/issues/13) after coordinating with R03 privacy and R06 authorization. Auth-only contract validation in CI does not imply acceptance of the club/member shapes.


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
