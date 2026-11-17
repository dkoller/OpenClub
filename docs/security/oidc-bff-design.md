# R05 — OIDC BFF security implementation design

**Current state (2026-11-17):** This design was implemented (R05/#14 closed after PR #71) and has run in synthetic CI ever since. Two scope statements below are superseded: Keycloak and **Rauthy** are both supported, with one issuer per installation ([ADR-0006](../decisions/0006-oidc-provider-choice.md)). Each installation also has exactly one club ([ADR-0005](../decisions/0005-single-club-installation.md)), so authorization resolves verified account → local member → explicit grants, not a "requested club". The text below is the original design record.

*Original status:* Proposed implementation detail for review (2026-11-14). Architecture is accepted in [ADR-0003](../decisions/0003-oidc-session-proposal.md), but nothing in this document implements login or authorizes handling real member data. Parent [#14](https://github.com/dkoller/OpenClub/issues/14), prerequisite [#11](https://github.com/dkoller/OpenClub/issues/11).

## Scope and trust boundaries

- One OIDC issuer **per OpenClub deployment** for MVP. Keycloak is the reference self-hosted provider; other conforming OIDC providers are configurable without provider-specific identity rules. Multiple issuers simultaneously are out of scope.
- React and the browser are **untrusted** for identity and authorization. Browser traffic uses a same-origin reverse proxy to the Ktor BFF. Ktor validates OIDC responses, creates/revokes sessions and owns all database lookups.
- Keycloak handles credentials, MFA and user authentication. Never build a second password database in OpenClub. All provider and database secrets come from runtime configuration; production startup fails if required secrets are missing.
- OIDC `issuer + subject` is the stable external identity key. Email may be useful as contact data but is **never** proof that an account belongs to an existing person. Accountless records need a separately verified invitation/linking process (#20).
- Authentication does not grant access to any club. Club-specific authorization in [#15](https://github.com/dkoller/OpenClub/issues/15) must resolve authenticated person -> requested club membership -> role. Initial administrator bootstrap is [#16](https://github.com/dkoller/OpenClub/issues/16).

## Library and protocol choices

- **Candidate implementation library:** `com.nimbusds:oauth2-oidc-sdk` (Nimbus OAuth 2.0 / OIDC SDK, Apache-2.0) for OIDC metadata, authorization/token exchange, signed ID-token validation and JWKS. Use a maintained, explicitly pinned release after dependency and API review in PR C, not a hand-written JWT or OIDC validator. Ktor handles routing, cookies and network lifecycle. Review transitive dependencies.
- Authorization Code flow with **PKCE S256**, confidential backend client credentials where supported. No implicit grant or resource-owner password flow.
- Validate discovery metadata against a configured HTTPS issuer (allow local HTTP solely in explicit dev mode). Use exact configured issuer, client ID, callback URI and endpoint allowlists. Do not discover arbitrary URLs from user input or follow redirects to untrusted hosts.
- For each login create independently random, single-use `state`, `nonce` and PKCE verifier bound to a short-lived browser login-transaction cookie. Store the state server-side in PostgreSQL (hashed lookup key), 5-minute TTL and consume it atomically on callback; reject missing/replayed/wrong state and mismatched nonce. Limit outstanding attempts per browser.
- Validate token signature using issuer-pinned JWKS and allowed algorithms, `iss`, `aud`/authorized party as applicable, expiry, not-before/issued-at tolerance, `nonce` and required `sub`; reject unexpected token types or unapproved signing algorithms. Cache and refresh JWKS with bounded settings; reject if trusted validation is unavailable.
- Use TLS in deployed environments, exact redirect URIs and no open redirect through a user-controlled `next` parameter. Callback path: `/api/v1/auth/callback`. Reject provider error responses safely without echoing codes or tokens.

## Session design

- **Store:** PostgreSQL via Flyway + jOOQ, using the existing database. No new Redis service for MVP. Hold session ID *hashes*, person IDs, created/last-used/absolute expiry and CSRF secret; restrict database access to API. Do not log cookies, session IDs, CSRF tokens or OIDC tokens. Use a dedicated bounded pool and an I/O dispatcher for blocking jOOQ/JDBC.
- **Cookie:** `__Host-openclub_session` in HTTPS production, `Secure; HttpOnly; SameSite=Lax; Path=/`, no `Domain`. Use a separately named `openclub_dev_session` for explicit loopback HTTP development only; never silently disable Secure in production.
- **Entropy:** at least 256 bits from a CSPRNG for opaque session handles; database stores a SHA-256 digest, not the raw value. Compare secrets in constant time where applicable; generate a fresh session upon successful authentication and revoke any previous session involved in that login (fixation protection).
- **Lifetime:** 30 minutes idle timeout and 12 hours absolute lifetime, configurable only within documented bounds; server-side expiry enforced for every request. Rotate the session handle on successful login and privilege-sensitive changes; invalidate upon logout and account/privilege revocation. Expired or unknown sessions receive `401`, never fallback to a provided person ID.
- No OAuth or refresh tokens in React localStorage, sessionStorage, browser URLs or accessible cookies. Any upstream refresh tokens retained by BFF must be encrypted at rest, minimized and expunged on revocation; prefer no refresh token in initial MVP unless required.

## CSRF and browser routes

- Authenticated state-changing requests (including logout) require a **session-bound synchronizer CSRF token** in `X-CSRF-Token`, plus an exact allowlisted `Origin` check; reject absent/mismatched/foreign/null origin. Return a CSRF token only via authenticated same-origin `GET /api/v1/auth/me` response; never in URLs or logs.
- All mutation endpoints reject non-JSON content types unless an explicit safe exception is designed, with no permissive CORS for credentialed cross-origin traffic. SameSite is defense in depth, not the only CSRF control.
- `GET /api/v1/auth/login` starts a login transaction but cannot create an authenticated session. `GET /api/v1/auth/callback` validates the one-time login state+nonce+PKCE before creating a session. The callback is an OAuth protocol exception to the mutation-CSRF-header rule; it uses transaction binding instead.
- `GET /api/v1/auth/me`: `200` with minimal authenticated person/session state and CSRF token, or `401`. `POST /api/v1/auth/logout`: `204`, revokes session and clears cookie; requires CSRF token. Response bodies and errors must not contain ID tokens, access tokens, code verifiers or internal identity-provider secrets.
- For OIDC provider logout, distinguish local session termination (mandatory) from provider single-sign-out (follow-up, if supported). Logout always invalidates OpenClub session even if provider logout is unreachable.

## Failure and abuse controls

- Explicit `401` for missing/invalid session, `403` for authenticated insufficient authorization; later R06 may use tenant-safe `404` to conceal existence across clubs. No member API before R06.
- Rate-limit login initiations, callbacks, and bad session/CSRF attempts. Do not expose token or exception details in client errors; use correlation IDs and non-sensitive server diagnostics.
- Trust forwarded scheme/host headers **only** from configured reverse proxies; derive the public callback origin from explicit configuration rather than untrusted Host/X-Forwarded-* headers.
- Keep login transaction and session tables separate, add indexes for expiration/revocation cleanup, and test that database outages fail closed.
- Recovery from lost club administrator access is **not** solved by authenticating with Keycloak; document and test the independently authorized, audited first-admin recovery mechanism in R07/#16 before pilot.

## Verification in follow-up PRs

1. Configuration rejects mismatched issuer, callback origin, insecure production cookies and absent secrets.
2. Mock-OIDC tests reject wrong signature/key, issuer, audience, nonce/state/PKCE, expired tokens and replayed callbacks.
3. Session tests cover stolen/malformed handles, expiry, logout, rotation/fixation, CSRF absence/mismatch, cross-origin requests and database failure.
4. HTTP integration tests establish that sessions cannot grant cross-club rights; these belong to R06/R15.
5. Keycloak local setup must be reproducible in Compose without committing real credentials or exposing development bootstrap defaults in production.

## References

- [RFC 9700 — OAuth 2.0 Security Best Current Practice](https://www.rfc-editor.org/rfc/rfc9700)
- [RFC 10017 — OAuth 2.0 for Browser-Based Applications](https://www.rfc-editor.org/rfc/rfc10017)
- [OWASP — CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- [Nimbus OIDC SDK](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk)


## Current single-club implementation (2026-11-15)

This was an early security design. The OIDC BFF/session architecture is implemented; the old proposed tenant-safe `404` across clubs is superseded by **one club per installation**. The local `GET /api/v1/club` returns 401 for no session and 403 for an authenticated but unlinked account. Cross-installation data isolation is an operations/database boundary, and club/team permissions remain R06/#76 work. See [ADR-0005](../decisions/0005-single-club-installation.md).
