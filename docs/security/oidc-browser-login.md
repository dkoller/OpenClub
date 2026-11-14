# R05 / C6 — browser OIDC login and callback

**Status:** opt-in authentication implementation and synthetic E2E verified; not a public release.
Parent: [R05/#14](https://github.com/dkoller/OpenClub/issues/14).

## Runtime and trust boundaries

With `OPENCLUB_AUTH_MODE=disabled` (default), the API still serves only
`/health`. An explicitly configured development or production OIDC runtime and
PostgreSQL database are required before Ktor registers login/callback routes.
Provider metadata and public signing keys are pinned to the configured issuer
and loaded before the server starts. A failing provider or DB aborts startup.

`GET /api/v1/auth/login` persists a random state, nonce, PKCE S256 verifier and
a separate hashed browser binding for five minutes. It sets the HttpOnly
five-minute pre-auth cookie and issues a 302 to the fixed configured authorization
endpoint. The handler accepts no arbitrary redirect target.

`GET /api/v1/auth/callback` rejects missing or repeated code/state, OIDC
provider errors, and invalid input. It clears the pre-auth cookie on every
outcome. A valid state is atomically consumed, including if the browser binding
is wrong. The confidential code exchange validates the signed ID token,
including configured issuer/audience/nonce, via existing internal verification.
Only a verified issuer+subject is used to resolve or create an unlinked person.
An existing browser session is revoked on successful re-login before issuing a
fresh, opaque PostgreSQL-backed session. Success sets an HttpOnly session cookie
and issues a 303 to the fixed local path `/`; no token or code reaches React.
Failed backend/provider operations return a generic 503, never provider secrets.

All JDBC execution uses `DatabaseRuntime.onDatabaseThread`; token exchange
runs on a limited I/O dispatcher. No club membership or admin grants are inferred.

## Validation and open gates

HTTP test-host cases cover default-disabled routes, login cookie/redirect,
missing/duplicate/provider-error callbacks, browser binding rejection and
successful cookie rotation. Existing DB/verification tests independently cover
one-time state consumption, replay, signature and subject mapping. The real
Keycloak/Chromium/PostgreSQL login, session and logout journey is exercised by
[CI](../development/auth-browser-e2e.md); [RSA key rotation](../development/auth-jwks-rotation-e2e.md)
is separately exercised against the same running API.

**Session follow-up (2026-11-14):** opt-in session routes are implemented; see [session API](session-api.md).

**Before public use:** complete R02 operational/recovery decisions, R03 privacy,
R06 club authorization, R07 first-admin bootstrap and the documented web `/api`
proxy-path fix. R05/#14 remains open until closeout review and CI; do not infer
public-release readiness from synthetic authentication CI.

### JWKS refresh implementation note (2026-11-14)

The code-exchange runtime now refreshes the startup-vetted JWKS endpoint with
a five-minute key lifetime and shared 30-second refresh cooldown. See
[provider discovery policy](oidc-provider-discovery.md#jwks-runtime-refresh-2026-11-14).
The earlier refresh follow-up is implemented. Session endpoints, process-local
[admission limits](auth-admission-limit.md), [expiry cleanup](auth-record-cleanup.md),
and real provider browser/rotation E2E have also been implemented and tested.
The rate limit is per API instance and does not replace edge abuse protection.
