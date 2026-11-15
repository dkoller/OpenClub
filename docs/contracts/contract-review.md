# R04 — OpenAPI contract review and approval gate

**Status:** Review findings, **not API acceptance**. See [draft contract](openapi-draft.yaml), [R04 #13](https://github.com/dkoller/OpenClub/issues/13) and [finding #37](https://github.com/dkoller/OpenClub/issues/37).

**Current review scope:** `openapi-draft.yaml` is the single-club working draft after PR #79; the historical findings below refer to the retired multi-club proposal, not current endpoints. Only authenticated `GET /api/v1/club` is implemented. R04 remains unapproved.

This review was written before pilot acceptance and R05 completion. The historical findings below are preserved; **R01/#10 is now closed** and OIDC/Ktor BFF architecture is selected/implemented. The draft contract remains **unapproved**, especially for member data ownership, field requirements and scoped permissions.

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
