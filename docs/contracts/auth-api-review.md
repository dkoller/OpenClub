# R05 — Browser auth HTTP contract

**Status:** Opt-in HTTP routes implemented; no public release approved. [OpenAPI proposal](auth-api-proposal.yaml); parent [R05/#14](https://github.com/dkoller/OpenClub/issues/14). This standalone auth contract is separate from the [frozen M1 contract](openapi-v1.yaml) (frozen as 1.0.0, now 1.1.0; ADR-0007) and the [M2 proposals draft](openapi-m2-draft.yaml).

## Integration boundary

The public same-origin route prefix is `/api/v1/auth`. Browser receives **only** an opaque, HttpOnly pre-auth binding cookie and, on successful callback, an opaque HttpOnly session cookie. Signed ID tokens, OAuth access/refresh tokens, PKCE verifier and client secret stay in Ktor. Login redirect is generated from pinned issuer settings; callback redirect is fixed to `/` (not a `next` parameter). State+browser binding expires after five minutes, is consumed atomically and cannot be retried. A callback is the narrowly scoped exception to the authenticated-mutation CSRF-header policy.

Use `__Host-openclub_login` and `__Host-openclub_session` for HTTPS with `Secure; HttpOnly; SameSite=Lax; Path=/` and no `Domain`. Explicit local loopback HTTP alone uses separate `openclub_dev_login` and `openclub_dev_session` without `Secure`. Never silently downgrade. A session has 30-minute server-side idle and 12-hour absolute expiration; cookie expiration is no substitute for database expiry. On successful callback, revoke any previous authenticated session presented by this browser before completing login and replace it with a fresh handle; overwriting the cookie does not revoke the old server-side session. Failure to persist revocation or the new session must fail closed without a successful redirect. Clear the pre-auth cookie on all callback outcomes. Do not surface OAuth values in browser-visible error messages, redirect locations, logs, or query strings after callback.

`GET /auth/me` returns a minimal `personId` and CSRF token only after server-side session validation. It returns **no memberships or permissions**. `POST /auth/logout` requires an exact configured `Origin`, JSON body `{}`, a session-bound `X-CSRF-Token` and a valid session; local revocation succeeds independent of IdP logout availability. SameSite is defense in depth, not a replacement for Origin+CSRF. Refuse credentialed cross-origin CORS. A session-database outage returns `503` for both `/me` and logout, distinct from `401` for an invalid session. `/me` must disclose no identity or CSRF token on an outage. Logout must not return `204` or clear the session cookie as proof of successful revocation when the database write cannot be confirmed.

## Security acceptance matrix

| Scenario | Expected boundary |
| --- | --- |
| Login start without configured issuer/secret or DB | Fail closed, no redirect |
| Login rate limit hit | 429, no transaction |
| Missing, expired, altered or reused state | Reject, no code exchange or session |
| Cookie missing or belongs to another browser | Reject and burn state, no session |
| Provider sends `error`, missing code or duplicated state/code | Reject, never echo parameters |
| Token wrong signature/issuer/audience/nonce/expiry | Generic failure without token claims in causes |
| Fresh unknown `issuer+subject` | Minimal unlinked person, **no club role** |
| Repeat verified identity login | Same person ID, fresh session handle; revoke the previous session presented by this browser |
| Successful callback with an existing session | Old handle rejected by `/me` and mutations; new handle works, with a fresh CSRF token |
| Callback cannot persist previous-session revocation or new session | 503, no successful login redirect or new session cookie |
| Session cookie missing/invalid/expired | `/me` 401, no CSRF token |
| Logout with missing/foreign/null Origin, wrong/missing CSRF or non-JSON | Reject, no session revocation |
| Logout with valid Origin/CSRF/session | Revoke in PostgreSQL, clear cookie, 204 |
| Database or provider outage during login/callback | 503, fail closed, never issue session |
| `/me` session-database outage | 503, no person ID or CSRF token; do not misreport as 401 |
| Logout session-database outage | 503, no 204 or cookie-clearing success claim; revocation remains unconfirmed |
| Any auth response | `Cache-Control: no-store`; no user-controlled redirect |
| Browser preflight from foreign origin | No credentialed CORS exemption |

## Implementation status and release boundary

The Ktor runtime pool/dispatcher, fail-closed OIDC configuration, cookie construction,
issuer-pinned JWKS refresh, browser-bound login/callback, rate limits, session
issuance, session routes, and Origin+CSRF guards have been implemented and tested.
The [OpenAPI contract](auth-api-proposal.yaml) records the implemented opt-in routes;
R05 closeout is pending merge and human review. This does not approve use with
real member data before privacy, club authorization, bootstrap and proxy gates.

No production identity, user account, seed password, external OAuth token or administrative privilege is created by this contract PR.

### Session HTTP implementation note (2026-11-14)

Build CI now validates all YAML OpenAPI documents in this directory with
openapi-spec-validator 0.7.2, including YAML parsing and OpenAPI schema/reference
validation. The contracts job runs independently of application path filters.
Locally: install that pinned validator and run
`openapi-spec-validator docs/contracts/auth-api-proposal.yaml` (repeat for
`docs/contracts/openapi-v1.yaml` and `docs/contracts/openapi-m2-draft.yaml`).

Following #64, opt-in `/api/v1/auth/me` and `/api/v1/auth/logout` are implemented.
Logout requires exact Origin, JSON `{}` and session-bound CSRF, with transactional
validation/revocation before cookie clearing. See [session API](../security/session-api.md).
Keycloak browser E2E, JWKS refresh, admission limits and cleanup are implemented.
R05 closeout still requires CI and independent review; club authorization,
bootstrap, privacy and web proxy integration remain release gates.
