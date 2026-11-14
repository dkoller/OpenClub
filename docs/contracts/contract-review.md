# R04 — OpenAPI contract review and approval gate

**Status:** Review findings, **not API acceptance**. See [draft contract](openapi-draft.yaml), [R04 #13](https://github.com/dkoller/OpenClub/issues/13) and [finding #37](https://github.com/dkoller/OpenClub/issues/37).

This document reviews the merged proposal without inventing decisions that depend on an unconfirmed pilot or unapproved authentication provider.

## What the current draft gets right

- Uses a server-enforced session security scheme rather than treating club IDs or roles as authentication.
- Models club-specific membership URLs and explicit `ADMIN` / `MEMBER` roles.
- Defines basic input schemas, bounded page size, and recognizable 401/403/404/409 outcomes.
- Separates person and membership references, matching [ADR-0002](../decisions/0002-identity-and-membership-model.md).

## Findings to resolve before acceptance

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
- [ ] Pilot fields and invitations decision incorporated.
- [ ] Testable examples for cross-club and last-admin rejection.
- [ ] Contract validation in CI.
- [ ] Version status changed from `0.0.0-draft` only after the above are approved.

This review introduces no runtime endpoint or new dependency. The draft remains non-binding.
