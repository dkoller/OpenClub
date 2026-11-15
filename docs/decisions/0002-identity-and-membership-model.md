# ADR-0002: Identity and club membership boundaries

- **Status:** Accepted
- **Date:** 2026-11-14
- **Scope:** MVP domain persistence and authorization design

## Decision

Separate **person**, **authentication identity** and **club membership**:

- A `person` is the minimal human record (`id`, `display_name`) and can exist before account creation; avoid collecting more personal data until needed.
- `person_identities` associates a person with an externally authenticated **issuer + subject** tuple. This tuple must come from a validated token/session and is unique across the database. Do not identify people by email address or trust client-provided person IDs.
- `clubs` have a UUID, name and unique human-readable slug.
- `memberships` are unique by (`club_id`, `person_id`). A person can join multiple clubs with **independent roles**. MVP roles: `ADMIN` (manage club members) and `MEMBER` (no membership administration). Roles are not global.
- An admin's club authorization must be checked server-side for **every** membership operation, including reads, writes, search and role changes. Database relationships alone do not enforce access restrictions.

## Auth design / undecided integration

*Historical note (2026-11-14):* At the time of this ADR the provider and session implementation were undecided. [ADR-0003](0003-oidc-session-proposal.md) now selects Keycloak as the reference OIDC provider with server-side BFF sessions. [R05/E1](../security/oidc-identity-resolution.md) implements internal verified issuer + subject resolution, without enabling login routes or adopting existing members.


Use an established OpenID Connect-compatible identity service rather than designing passwords, sessions or token signing ourselves. The **specific provider, login UI, token validation library and session architecture remain undecided**. No sign-in or admin/member endpoints are introduced in this PR.

When auth is implemented: validate signature, issuer, audience, expiry and other necessary token claims; resolve verified issuer+subject to a person; confirm an active membership with the required role for the **requested club**. Do not take identity, authorization role or club membership from arbitrary browser headers or JSON. Protect against first-admin bootstrap and last-admin lockout. Never expose cross-club data to callers lacking explicit access. Choose an audit/retention policy before handling live membership data.

## Migration strategy

`V2__identity_and_memberships.sql` adds the tables and club slug. Existing clubs receive a stable generated `club-<uuid>` slug; their names and UUIDs are unchanged. No seeded people, permissions or administrative accounts. In particular, new tables do **not** constitute a secure membership API or row-level security policy.

## Deferred

Invitations, membership status/lifecycle, contact details, guardian relationships, deletion workflows, multi-role assignments, organization hierarchy and sport-specific roles are intentionally omitted until requested by the first pilot.


## Superseding R01 product requirements (2026-11-15; design reconciliation required)

The original accepted schema in this ADR is historical evidence of the implemented foundation, **not** acceptance of its suitability for the current member-data policy. Merged R01 PRs [#72](https://github.com/dkoller/OpenClub/pull/72) and [#73](https://github.com/dkoller/OpenClub/pull/73) require **strictly club-local personal data**: name, contact information, date of birth, gender and any future personal details must never be read, copied, inferred, auto-filled or synchronized between clubs, even when one verified OIDC identity is related to more than one club. Globally resolved authentication references may support login, but must not themselves expose a cross-club personal directory.

The existing global `person` and `memberships (club_id, person_id)` design must be reviewed before implementing member CRUD, including the definition of club-owned records, DTO boundaries, identity linkage and safe migrations. Do **not** assume adding fields to the global `person` table satisfies this requirement. Document any new architecture in an explicit follow-up ADR; do not rewrite the history of schema V2. Track the dedicated reconciliation under [#74](https://github.com/dkoller/OpenClub/issues/74), and coordinate with [R04/#13](https://github.com/dkoller/OpenClub/issues/13) and [R06/#15](https://github.com/dkoller/OpenClub/issues/15), with privacy review under [R03/#12](https://github.com/dkoller/OpenClub/issues/12). No live club-member records until isolation is implemented and tested.
