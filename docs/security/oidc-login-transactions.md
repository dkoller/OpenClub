# R05 / C2 — One-time OIDC login transactions

**Status:** Historical note on the initial primitive. R05 login, callback and authenticated sessions are now implemented; fresh one-club schema is in `V1__single_club.sql`.

[PR #51](https://github.com/dkoller/OpenClub/pull/51) constructs PKCE S256 requests with independently generated state and nonce. This change adds Flyway `V3__oidc_login_transactions.sql` and `OidcLoginTransactions` with jOOQ over PostgreSQL.

- `start()` stores SHA-256 digests of state and a 256-bit random browser binding, plus confidential nonce/verifier, with a five-minute expiry based on PostgreSQL time; it returns the redirect request and the raw binding **only to trusted backend code**.
- A future route must persist the transaction **before** redirecting and set the browser binding in a secure, HttpOnly, appropriately scoped short-lived cookie. These pieces are not yet wired.
- `consume(state, cookie)` atomically deletes the transaction using `DELETE ... RETURNING`. Incorrect bindings, invalid/missing values, expired transactions and replays fail closed. Consumption does **not** validate ID tokens or create sessions.
- `pruneExpired()` is provided for future scheduled cleanup; **no background cleanup is running yet**. Nonce/verifier are sensitive while pending: restrict DB permissions, keep them out of logs and backups where practicable, and enable short retention. These are synthetic test credentials only.
- jOOQ is synchronous JDBC. Future HTTP handlers must invoke this store on a dedicated I/O dispatcher, not a Ktor event-loop thread. The current bootstrap only opens a temporary pool for migrations.
- PostgreSQL integration tests require `DB_URL`, `DB_USER` and `DB_PASSWORD` (CI supplies these). They cover valid single use, wrong browser binding, malformed/missing input, replay, expiry and pruning.

**Remaining before exposing login:** keycloak/public vs container-internal issuer routing; OIDC discovery/JWKS and ID-token verification with a maintained SDK; safe callback and code exchange; rate limiting; cookie security and session creation, revocation and CSRF from later R05 PRs. No OpenAPI change because this adds no HTTP routes. See [BFF design](oidc-bff-design.md) and [R05 issue #14](https://github.com/dkoller/OpenClub/issues/14).


**Reset:** Authentication transactions retain their tested hash/expiry semantics; only Flyway version history is replaced for the never-live pre-production schema. See [ADR-0005](../decisions/0005-single-club-installation.md).
