# R05 / C6 — browser OIDC login and callback

**Status:** review-required integration slice; not a public authentication release.
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
one-time state consumption, replay, signature and subject mapping. Real
end-to-end login remains unverified until the subsequent CI slice.

**Before public use:** complete `GET /auth/me`, CSRF+Origin guarded logout,
JWKS refresh, rate limits, deterministic Keycloak browser E2E testing and
privacy/first-admin authorization release gates. Keep #14 open and require
manual PR review/merge.

### JWKS refresh implementation note (2026-11-14)

The code-exchange runtime now refreshes the startup-vetted JWKS endpoint with
a five-minute key lifetime and shared 30-second refresh cooldown. See
[provider discovery policy](oidc-provider-discovery.md#jwks-runtime-refresh-2026-11-14).
This supersedes the earlier refresh follow-up only; rate limits, session endpoint
verification and real provider browser/rotation E2E remain release gates.
