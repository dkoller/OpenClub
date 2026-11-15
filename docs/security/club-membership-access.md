# Installation-local member role lookup

**Current implementation:** `org.openclub.api.members.MemberAccess`. The former `ClubMembershipAccess(personId, clubId)` multi-tenant API is retired in the single-club reset.

`MemberAccess.roleFor(accountId)` returns `ADMIN`, `MEMBER`, or `null` by looking up one member in this installation's sole club. No role is granted just by authenticating with OIDC. The account ID must come from a verified server session, never a user-supplied member or account identifier.

**Security boundary:** This PostgreSQL lookup is not itself an HTTP authorization system; privileged member/team/grant operations still require R06 authorization and CSRF/Origin checks. `GET /api/v1/club` is currently guarded by this relationship. PostgreSQL `MemberAccessTest` covers authorized admin/member and an outsider. No cross-club lookup or switcher is supported.

**Privacy/release:** See [single-club architecture](../decisions/0005-single-club-installation.md) and [R03](https://github.com/dkoller/OpenClub/issues/12). Tests use synthetic identities only.
