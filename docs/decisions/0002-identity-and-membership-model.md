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

Use an established OpenID Connect-compatible identity service rather than designing passwords, sessions or token signing ourselves. The **specific provider, login UI, token validation library and session architecture remain undecided**. No sign-in or admin/member endpoints are introduced in this PR.

When auth is implemented: validate signature, issuer, audience, expiry and other necessary token claims; resolve verified issuer+subject to a person; confirm an active membership with the required role for the **requested club**. Do not take identity, authorization role or club membership from arbitrary browser headers or JSON. Protect against first-admin bootstrap and last-admin lockout. Never expose cross-club data to callers lacking explicit access. Choose an audit/retention policy before handling live membership data.

## Migration strategy

`V2__identity_and_memberships.sql` adds the tables and club slug. Existing clubs receive a stable generated `club-<uuid>` slug; their names and UUIDs are unchanged. No seeded people, permissions or administrative accounts. In particular, new tables do **not** constitute a secure membership API or row-level security policy.

## Deferred

Invitations, membership status/lifecycle, contact details, guardian relationships, deletion workflows, multi-role assignments, organization hierarchy and sport-specific roles are intentionally omitted until requested by the first pilot.
