# Internal team-roster visibility (pre-production)

**Status (updated 2026-11-17):** The roster is now exposed to authorized users as
`GET /api/v1/teams/{teamId}/members`, with assignment through `POST`/`DELETE
/api/v1/teams/{teamId}/members/{memberId}` (`TeamMemberHttpRoutes.kt`, OpenAPI draft).
The DE/EN/FR/IT browser roster is in `apps/web/src/TeamMembers.tsx`. The rules below still apply:
the roster requires both the directory grant and a club-wide or exact-team team grant, and the
query itself requires a current verified identity. There is still **no approved
identity/contact exposure policy and no permission to use real member data**.

*Original status (pre-HTTP): "Internal SQL only. There is no HTTP endpoint, browser roster,
approved identity/contact exposure policy or permission to use real member data."*

`TeamMemberDirectory.list(verifiedAccountId, teamId, limit, after)` returns a
bounded, deterministically ordered **single-team** member-name page (local UUID,
first name, last name only). It requires both:

1. A currently linked, active local member with an explicit **club-wide**
   `VIEW_MEMBER_DIRECTORY` grant; and
2. A current club-wide `VIEW_TEAMS`/`MANAGE_TEAMS` grant **or** the matching
   **exact team's** `VIEW_TEAMS`/`MANAGE_TEAMS` grant.

The SQL checks these grants as part of **every** read. A verified login alone,
a team grant without directory visibility, and a directory grant without team
visibility all return no member names. The caller cannot use permission on one
team to see the roster of another. An account unlink, archival or revocation
blocks later pages immediately. Unknown teams also yield no results.

The query binds all UUIDs and pagination fields as SQL parameters; pages are
limited to 1–50 records (default 20), sorted by last name, first name and UUID.
The `After` tuple is **internal**, not an API cursor or signed public token.
No date of birth, phone, email, player gender or account link is read or
returned. Tests use synthetic PostgreSQL fixtures and cover scoped and
club-wide grants, denied access, revocation and pagination.

**Release gates:** R03/#12 must approve the display of member names,
especially minors' names, before a web/API route exists. R04/#13 must approve
request authentication, cursor integrity, error/404 behavior, response-cache
protection and field visibility. The backend must obtain the account ID from
a verified server session; never accept it from an untrusted request.
See [capability security](club-membership-access.md), [#75](https://github.com/dkoller/OpenClub/issues/75)
and [#76](https://github.com/dkoller/OpenClub/issues/76).
