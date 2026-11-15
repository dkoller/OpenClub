# Installation-local member role lookup

**Current implementation:** `org.openclub.api.members.MemberAccess`. The former `ClubMembershipAccess(personId, clubId)` multi-tenant API is retired in the single-club reset.

`MemberAccess.authorizedClubId(accountId, permission)` now performs a single database-side, installation-local club+membership check. `VIEW_CLUB` permits linked ADMIN and MEMBER roles; interim `MANAGE_MEMBERS` permits only ADMIN. A null result means denied. Only server code selects the permission and the account UUID must come from a verified session. Existing `GET /api/v1/club` uses this check, returning 401 for missing authentication and 403 for authenticated accounts without an authorized local member. These are **interim** checks until #76 defines configurable capabilities and #13 approves member mutations; no member-write HTTP route is enabled.

`MemberAccess.roleFor(accountId)` returns `ADMIN`, `MEMBER`, or `null` by looking up one member in this installation's sole club. No role is granted just by authenticating with OIDC. The account ID must come from a verified server session, never a user-supplied member or account identifier.

**Security boundary:** The lookup is a server-side authorization building block, not an automatic Ktor middleware. Any future state-changing HTTP route must separately enforce trusted session, approved permission, CSRF and Origin before touching private data. `GET /api/v1/club` now invokes the common guard. PostgreSQL `MemberAccessTest` covers authorized admin/member and an outsider. No cross-club lookup or switcher is supported.

**Privacy/release:** See [single-club architecture](../decisions/0005-single-club-installation.md) and [R03](https://github.com/dkoller/OpenClub/issues/12). Tests use synthetic identities only.

## Internal member directory foundation (R09/#18; not an HTTP API)

`MemberDirectory.search(accountId, query, limit, after)` is a PostgreSQL-only query for a trusted installation-local session account ID. It checks the acting account's linked member **in the same SQL statement** as the read. No caller-supplied role, club ID, or tenant selector exists; an unlinked account gets an empty result. Only local UUID, first name and last name are selected; birth dates, contact details, gender and login identities are never loaded into the summary. Queries use bound parameters and literal substring matching, not wildcard matching; names sort deterministically by last name, first name, then UUID. Limit is 1–50, default 20; the next-page key is an **internal typed value**, never a public, unsigned cursor.

**Exposure gate:** No Ktor route is registered for this query. R03/#12 must approve directory visibility (particularly children), R06/#15 and #76 must approve fine-grained capabilities, and R04/#13 must finalize an authenticated, tamper-resistant opaque public cursor before a member-directory HTTP endpoint is enabled. Do not mistake this internal query or synthetic SQL test for approval to handle real member data.
