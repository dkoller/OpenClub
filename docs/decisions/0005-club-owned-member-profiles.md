# ADR-0005 (proposed): Single-club OpenClub installations

- **Status:** Proposed for human review; not implemented
- **Date:** 2026-11-15
- **Decision owner:** OpenClub project owner
- **Tracks:** [#74](https://github.com/dkoller/OpenClub/issues/74), [R04/#13](https://github.com/dkoller/OpenClub/issues/13), [R06/#15](https://github.com/dkoller/OpenClub/issues/15), [R18/#27](https://github.com/dkoller/OpenClub/issues/27)
- **Supersedes:** Multi-club application tenancy, global/shared person profiles and club-switching goals in ADR-0002 and the original roadmap. Historical migrations remain immutable.

## Decision

**One OpenClub installation serves exactly one club and owns one independently operated PostgreSQL database.** The codebase is reusable by any club; the software is not hardcoded to the pilot floorball club. The first setup establishes the one club and its first explicitly authorized administrator. No self-service creation of additional clubs in a running installation.

Club data must never be shared, copied, queried, auto-filled or synchronized between independent installations. Distinct club installations have no shared application database, member registry, login directory or central multi-club identity broker provided by OpenClub. Existing OIDC provider support remains; an operator may independently choose an external identity provider, but no OpenClub cross-installation membership discovery or linking is allowed.

The first usable release still requires member management, team assignments, configurable club-wide and team-scoped permissions, and German, English, French and Italian. First/last name and date of birth are mandatory for every person; email **and** phone are mandatory for adults (optional for minors); gender is mandatory for players only when that club enables the rule. R03 must define the adulthood threshold, privacy and lawful processing rules.

## Conceptual target, not an instruction to rewrite working auth

| Target concept | Responsibility |
| --- | --- |
| Installation / configured club | Exactly one club; immutable identity after setup except an authorized recovery/migration procedure |
| Member | One local record with personal data and club membership lifecycle; exists before login |
| Account | Verified OIDC issuer/subject identity for **this installation**; may be linked to a local member only by explicit secure process |
| Team and assignment | Local teams; one member may belong to several |
| Capability grant | Local club-wide or team-specific permission; delegated only by expressly authorized administrators |

**No global person entity is required in the target domain.** A member may exist without an account; an account may exist without being linked to a member. Matching names or email cannot establish account ownership. One login per installation is enough; do not implement account cross-club navigation.

## Compatibility with the current repository

Existing Flyway V2 created `clubs`, `persons`, `person_identities` and `memberships`. Current `VerifiedPersonIdentities.resolveOrCreate` stores verified issuer/subject mappings via a placeholder `persons` row and returns a UUID used by sessions; `ClubMembershipAccess` uses `person_id + club_id` to look up a role. This is working **authentication plumbing**, not a reason to keep a global person in the future domain.

Do **not** rewrite V2, break R05 login/session behavior or create a second independent identity system in one unreviewed change. Sequence the transition:

1. **Installation boundary:** enforce at most one club row for new and existing deployments; define clear fail-closed startup/upgrade behavior for legacy databases already containing more than one club. Never automatically collapse, merge or erase clubs. Existing multi-club databases require operator-guided split/export migration before enforcing the singleton invariant. Document backup/restore and setup semantics.
2. **Data model:** introduce installation-local member records with their required fields. Choose a minimal additive migration and explicit association to the existing verified identity anchor, keeping the legacy `persons` record as a temporary internal auth compatibility detail. Do not treat its `display_name` as authoritative membership data or expose it in member APIs. A later reviewed refactor can rename this to `accounts` and adjust foreign keys/session IDs; **no permanent global person concept is required**.
3. **Account linking:** securely link an existing account to an accountless member record only on explicit verified proof. Do not infer links from email/name or automatically grant admin status.
4. **Authorization and UI:** resolve the sole installation club server-side; no public `clubId` selection, club switching or cross-club discovery. Keep least-privilege membership/team permission checks and last-permission-admin protection. Do not remove authentication or enforce all access via UI only.
5. **Cleanup:** after regression tests, remove unused multi-club endpoints, authorization branches, navigation and schema elements through subsequent small reviewed migrations. Existing historic schema may temporarily contain a `club_id` column during compatibility transition; this does **not** authorize multiple active clubs.

## Explicit guards and acceptance tests

- A new installation can provision exactly one club through a controlled first-admin bootstrap. A second club creation attempt fails server-side; startup/upgrade rejects unsafe multi-club database state without loss of data.
- Accounts created through verified OIDC login do not automatically gain member or administrative permissions. Member records can be entered before account creation.
- Member data cannot cross installations because each deployment uses its own database and application state; no OpenClub cross-instance aggregation, search or sync. Validate deployment configuration so installations do not accidentally point at the same DB/schema.
- Every sensitive action checks trusted session identity and granted local club/team capability; tests cover unauthorized reads/edits, team-scope escape, grant escalation/revocation and final authorized administrator safeguards.
- Mandatory birth date and adult contact validation are server-side; player gender requirement is a local club policy, not inferred from club type. No unrestricted exposure of full birth dates or contacts before R03 sign-off.
- Keep existing Keycloak/PostgreSQL authentication, issuer-bound validation, session and collision tests passing during migration. No live data before R03, R06, R15 and deployment/backup requirements are met.

## Consequences and work removed

Eliminate tenant routing and club switching from the first-release UX, and do not design globally reusable person profiles, club-to-club membership reconciliation, or APIs for discovering another club's data. The remaining `clubId` columns and joins can be retained temporarily as compatibility details until migrations are safe. The former multi-club cross-data-leak test suite should become **single-club invariant, local authorization and deployment separation** tests rather than being removed without replacement.

Separate installations need independent configuration, TLS, updates, backups and recovery. R18 must explicitly evaluate cost/operability for volunteer-run clubs. Managed hosting might run many isolated installations later; it must not imply shared tenant storage.

## Open design decisions and scope boundary

The exact existing-installation migration strategy, account/member linkage, legacy multi-club data upgrade behavior and adult age threshold remain for implementation/security/privacy review. This ADR proposes architecture only: **no database, Kotlin, React, OpenAPI or deployment changes are made in this PR**. Review R04, R06, R07 and R18 before implementing; keep #74 open until the requisite changes are delivered and tested. Existing R01 requirements acceptance remains closed; runtime demo approval remains under R19.
