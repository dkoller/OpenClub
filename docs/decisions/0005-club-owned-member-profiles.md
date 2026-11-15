# ADR-0005 (proposed): Club-owned member profiles with shared authentication

- **Status:** Proposed for human review; not yet accepted or implemented
- **Date:** 2026-11-15
- **Tracks:** [#74](https://github.com/dkoller/OpenClub/issues/74), [R03/#12](https://github.com/dkoller/OpenClub/issues/12), [R04/#13](https://github.com/dkoller/OpenClub/issues/13), [R06/#15](https://github.com/dkoller/OpenClub/issues/15)
- **Supersedes for personal data:** ADR-0002's inference that a global `person` can safely hold member details; retains its verified OIDC identity binding as an implementation fact

## Context: verified repository state

Flyway V2 currently defines `persons(id, display_name, created_at)`, `person_identities(person_id, issuer, subject)` (unique issuer/subject), and `memberships(id, club_id, person_id, role)` (unique club/person). `VerifiedPersonIdentities.resolveOrCreate` validates the issuer/subject, creates a synthetic placeholder `persons` record for an unlinked login, and resolves concurrent logins without adopting memberships based on name/email. `ClubMembershipAccess.roleFor(personId, clubId)` performs a club-scoped role lookup. Browser auth and session code depend on this verified global identifier.

R01 now requires **no club-to-club personal-data sharing, synchronization or autofill**, even for the same authenticated login. First name, last name and date of birth are mandatory for each person in each club; email and phone are both required for adults, optional for minors; a club may require gender from players. This cannot be implemented by adding member personal fields to the existing globally shared `persons` row.

## Proposed decision

**Maintain the existing identity resolver/session contract for the first migration; create an independently owned club member profile per membership.** The term `persons` in the current schema describes an internal authentication-link anchor, *not* a reusable member profile. No member-facing or contact/demographic fields may be added to it.

Proposed storage (names illustrative; schema changes require a separate reviewed PR):

| Entity | Fields / responsibility | Boundary |
| --- | --- | --- |
| `persons` | Existing opaque UUID and legacy placeholder `display_name` | Authentication linking only; placeholder is not authoritative member data |
| `person_identities` | Existing verified unique `issuer + subject -> person_id` | Authentication only; never use OIDC email or name claims to adopt member records |
| `memberships` | Existing UUID, `club_id`, `person_id`, role | Membership/role relation, scoped to club |
| **`club_member_profiles`** | `membership_id` (unique FK), `club_id`, `first_name`, `last_name`, `date_of_birth`, nullable `email`, `phone`, `gender` | Club-owned record. Every read, write, search, archive and export is scoped to `club_id` and authorized membership/permission |
| Club policy (future) | `gender_required_for_players` (default not assumed) | Configured by club administrators; player status is a separate domain relationship |

**Every club gets a distinct profile row, even if the same `person_id` appears in several memberships.** Shared authentication does not imply any shared member field. Two clubs may independently store different values, including for name or date of birth; no automatic consistency or reconciliation between them.

Use a composite relationship (e.g. `UNIQUE (id, club_id)` on `memberships`, and `FOREIGN KEY (membership_id, club_id) REFERENCES memberships(id, club_id)`) so the profile cannot be attached to a membership belonging to a different club. This does **not** replace server-side authorization. Query profiles only through `club_id` and membership/permission checks, never by global `person_id` alone. Avoid generic profile endpoints that take a global person UUID.

## Required invariants and flows

1. **Accountless creation:** An authorized club administrator can create a membership and a profile with no login. First/last name and date of birth are mandatory. If calculated adult status applies, both email and phone must be present. When the club requires player gender, enforce it when activating player status. Do not assume a team assignment always denotes player status.
2. **Existing login:** The verified issuer/subject mapping continues to yield an opaque person UUID; any club-specific profile is resolved only *after* a membership check for the requested club. The login response must not return aggregated profiles. An authenticated account with no memberships receives no personal member information.
3. **Invitation/account linking (R11):** Only explicit verified linking may connect an existing club membership to the login anchor; never search by email/name or silently claim a record. Existing `memberships.person_id` uniqueness and foreign keys mean link/relink semantics require a careful transaction design; **do not implement or infer linking in this ADR**.
4. **Club switching:** Change the active club's authorization and profile query context; clear prior-club cached UI data and invalidate stale requests. Do not auto-populate the new club's form with the old club's fields.
5. **Club-local edits and erasure:** Editing/archiving/exporting/erasing a club profile must not alter another club's profile or global authentication binding. Audit logs must reference club and membership IDs and avoid raw sensitive fields.
6. **Scoped permission grants:** Future team-scoped edits must not allow edits to members outside authorized team relationships, nor permit identity binding or permission-administration changes.
7. **Privacy:** Do not expose date of birth, gender or contact information in unrestricted club directory responses. R03 must approve purpose, allowed viewers, minors' protections, retention, export and deletion before real data. A global internal identifier should not be a public discoverability key.

## Migration and rollout plan (separate PRs)

1. **Design/contract gate:** Review this ADR with R03, R04 and R06. Decide the local age-of-adulthood threshold, how it is applied, representation of gender, and what verified account-linking must do with the old `person_id` relationship. Explicitly decide how to handle any legacy non-placeholder `persons.display_name` rows; do not copy them to every club.
2. **Additive Flyway migration:** Add a new club-local table and composite constraints without modifying V2 migration history or changing live login behavior. If no real member data exists, require new rows to have complete mandatory fields; if legacy records exist, use a separately reviewed, privacy-safe backfill/quarantine strategy before `NOT NULL` enforcement. Schema tests must check mismatched club FK rejection.
3. **Repository/service boundary:** Introduce club-qualified read/write APIs and access checks that accept trusted actor/session plus requested `club_id`; forbid profile lookup by global `person_id` alone. Test a single verified identity with two memberships whose profiles deliberately disagree on every personal field.
4. **Contract and UI:** Promote only a reviewed R04 OpenAPI revision; never implement the current historical draft unchanged. Server-side enforce adult email+phone, mandatory DOB and configured player gender, with birth-date-derived adult status; build UI without cross-club autofill.
5. **Security/regression:** Preserve R05 issuer/subject uniqueness, collision handling, sessions and verified identity behavior. Include authorization denial, pagination, search, export and club switching negative tests; complete R03 and R15 release gates.

## Why this option, and rejected shortcuts

- Reuses working, security-tested OIDC identity resolution and avoids a premature redesign of verified global login semantics.
- Gives a concrete club-owned row and database cross-club relationship check while maintaining a simple member-facing API path `/v1/clubs/{clubId}/members/{membershipId}`.
- **Reject:** adding DOB/email/phone/gender to `persons` (cross-club data coupling); cloning OIDC identities separately for each club (breaks one login and unique issuer/subject); trusting client-supplied `personId` or email to choose a member; API filtering without a real club ownership constraint.
- **Tradeoff:** existing `persons.display_name` is legacy personal-ish content and must never be surfaced as a club member name. A future auth-only identifier refactor is possible but is **not a prerequisite for this incremental plan** if strict read/DTO controls are demonstrated.

## Acceptance of this ADR is not completion of #74

This is a proposed architectural choice only. #74 remains open until the reviewed architecture is implemented or the issue's implementation boundary is explicitly split, with database and negative security tests. R03/R04/R06, team assignments #75 and scoped grants #76 remain separate work. No real personal data is authorized for use by this proposal.
