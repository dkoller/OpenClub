# Club membership access lookup

**Status:** Internal persistence primitive; **not authentication or HTTP authorization**.

`ClubMembershipAccess` answers which `ADMIN` or `MEMBER` role a known person has **within one requested club**. It returns `null` for a missing membership, even when the person belongs to another club. There is no global administrator role.

The caller's `personId` **must** be obtained by the future session/authentication layer from a verified OIDC `issuer + subject`, never from a URL, query argument, browser header or request JSON. This class does not verify tokens or sessions and must not be wired directly to public routes. It intentionally performs only a scoped SQL read.

PostgreSQL-backed `ClubMembershipAccessTest` covers cross-club role differences, ordinary members and absent memberships, and uses synthetic identities. Run with `DB_URL`, `DB_USER`, `DB_PASSWORD` and `./gradlew test` (see [scaffold instructions](../concepts/ai/scaffold.md)).

Before exposing club/member routes, complete [R05 authentication](https://github.com/dkoller/OpenClub/issues/14), [R06 authorization](https://github.com/dkoller/OpenClub/issues/15) and first-admin bootstrap [R07](https://github.com/dkoller/OpenClub/issues/16). The provider and session design remain **proposed** in [ADR-0003](../decisions/0003-oidc-session-proposal.md). These tests do **not** substitute for end-to-end HTTP authorization tests.
