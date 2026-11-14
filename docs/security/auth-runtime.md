# R05 / C5-D2 — process-scoped backend runtime

**Status:** Internal wiring only. No login, callback, `/auth/me`, logout or member routes are enabled. Parent [R05/#14](https://github.com/dkoller/OpenClub/issues/14).

## PostgreSQL ownership and bounded JDBC work

On startup, `DatabaseRuntime.openIfConfigured` creates a **single process-scoped** Hikari pool (maximum four connections), runs Flyway and connectivity validation and retains that pool until Ktor terminates. A `finally` block closes the pool when the server stops. The `onDatabaseThread` suspend helper dispatches blocking jOOQ work to four named dedicated threads. Existing internal stores remain synchronous and **must only be invoked through this dispatcher** by future HTTP handlers. Do not use blocking JDBC or synchronous provider fetches on Ktor event loops. On a failed migration/connectivity check, pool setup closes and startup fails. Existing scaffold mode without `DB_URL` still serves `/health` and cannot enable authentication.

This is deliberately a small infrastructure slice: no auth service is yet injected into Ktor routes, no request can create a session and there is no background pruning job. Review resource lifecycle and failure paths before wiring login.

## Fail-closed OIDC runtime configuration

`OPENCLUB_AUTH_MODE` defaults to `disabled`. Only `development` and `production` opt in; partial OIDC credentials in disabled mode or an incomplete enabled config fail startup rather than silently changing security. Required when enabled: `OPENCLUB_OIDC_ISSUER`, `OPENCLUB_OIDC_AUTHORIZATION_ENDPOINT`, `OPENCLUB_OIDC_CLIENT_ID`, `OPENCLUB_OIDC_CLIENT_SECRET` (at least 32 chars), `OPENCLUB_OIDC_REDIRECT_URI`, `OPENCLUB_PUBLIC_ORIGIN` and a configured PostgreSQL database. These values are never defaulted to real credentials or read from browser headers.

Production requires HTTPS for configured issuer and public callback origin. Development is **explicit loopback HTTP only** for both issuer and browser, matching the optional Keycloak [backchannel](../development/keycloak-local.md); `OidcSettings` also requires authorization origin to match issuer and exact callback path. Callback scheme, host and port must equal `OPENCLUB_PUBLIC_ORIGIN`. This config class is not a permission to expose auth routes before all security controls exist. The client secret is kept in a non-data class with a redacted `toString()`; no secret is logged.

## Cookie contract (not yet emitted)

`AuthCookiePolicy` constructs Ktor `Cookie` instances for 43-character URL-safe random handles only. Production: `__Host-openclub_login` (five-minute pre-auth binding) and `__Host-openclub_session` (maximum 12-hour browser cookie), `Secure; HttpOnly; SameSite=Lax; Path=/`, no Domain. Explicit loopback development uses `openclub_dev_login` and `openclub_dev_session`, without Secure. Clearing uses matching name/path/flags and Max-Age=0. Server-side session expiration remains authoritative (30-minute idle and 12-hour hard limit).

No cookie is currently issued. The next HTTP PRs must implement the exact same-origin Origin and CSRF checks, reject unsafe redirects, and ensure the pre-auth cookie is cleared on all callback outcomes. [Proposed browser auth API](../contracts/auth-api-proposal.yaml) is a separate parallel workstream.

## Validation

Kotlin tests cover default-disabled/partial config, HTTPS vs explicit loopback, issuer and callback origin pinning, redacted secrets and cookie attributes/format. PostgreSQL integration test exercises bounded-dispatcher query on a real CI database, with configuration failure tests. Neither real credentials nor accounts are used. Health response remains unchanged; no OpenAPI routes changed.
