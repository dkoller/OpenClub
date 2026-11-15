# Installation-local member role lookup

**Current implementation:** `org.openclub.api.members.MemberAccess`. The former `ClubMembershipAccess(personId, clubId)` multi-tenant API is retired in the single-club reset.

`MemberAccess.authorizedClubId(accountId, permission)` now performs a single database-side, installation-local club+membership check. `VIEW_CLUB` permits linked ADMIN and MEMBER roles; interim `MANAGE_MEMBERS` permits only ADMIN. A null result means denied. Only server code selects the permission and the account UUID must come from a verified session. Existing `GET /api/v1/club` uses this check, returning 401 for missing authentication and 403 for authenticated accounts without an authorized local member. These are **interim** checks until #76 defines configurable capabilities and #13 approves member mutations; no member-write HTTP route is enabled.

`MemberAccess.roleFor(accountId)` returns `ADMIN`, `MEMBER`, or `null` by looking up one member in this installation's sole club. No role is granted just by authenticating with OIDC. The account ID must come from a verified server session, never a user-supplied member or account identifier.

**Security boundary:** The lookup is a server-side authorization building block, not an automatic Ktor middleware. Any future state-changing HTTP route must separately enforce trusted session, approved permission, CSRF and Origin before touching private data. `GET /api/v1/club` now invokes the common guard. PostgreSQL `MemberAccessTest` covers authorized admin/member and an outsider. No cross-club lookup or switcher is supported.

**Privacy/release:** See [single-club architecture](../decisions/0005-single-club-installation.md) and [R03](https://github.com/dkoller/OpenClub/issues/12). Tests use synthetic identities only.
