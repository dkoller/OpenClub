# R05 session HTTP API

Authentication remains opt-in. Use the existing [runtime configuration](auth-runtime.md)
and [local Keycloak setup](../development/keycloak-local.md); no new variables.
The configured public origin is canonicalized at startup to browser Origin form:
lowercase host, compressed IPv6 literal (first longest zero run), and no explicit
default port (HTTPS 443 / HTTP 80). Scoped IPv6 literals are not supported.
Only that exact Origin header is accepted for logout; inbound headers are never normalized.
Forwarded/Host headers never establish trust.

After browser login, same-origin GET /api/v1/auth/me with the HttpOnly session
cookie returns only personId and csrfToken. Unknown, malformed, expired and
revoked sessions return 401; storage failure returns generic 503.
All session responses carry Cache-Control: no-store. No OAuth token or club
membership/role is returned. Do not log the response's CSRF token.

POST /api/v1/auth/logout requires the session cookie, exact Origin,
Content-Type: application/json, X-CSRF-Token from /me and body {} (up to 256 bytes,
JSON whitespace allowed). Cross-origin/missing/duplicate Origin returns 403;
missing/wrong CSRF for an active session returns 403. Invalid session returns 401.
Bad content type/body returns 400. No credentialed CORS is enabled.

Logout locks the active session row, compares its CSRF secret in constant time
and deletes it within the same jOOQ transaction on the bounded DB dispatcher.
Concurrent logout/rotation/re-login serialize on that row. Invalid CSRF does not
extend idle expiry. Only after commit does the response expire the same cookie
name/path/security policy and return 204. Storage failure returns 503 without
clearing the cookie or claiming success. An already revoked session returns 401.
This terminates only the OpenClub session; a later login may reuse Keycloak SSO.

Run apps/api/gradlew test (gradlew.bat on Windows) with DB_URL/DB_USER/DB_PASSWORD
pointing to a migrated synthetic PostgreSQL database. SessionRoutesTest exercises
the HTTP boundary; AuthSessionsTest covers the actual PostgreSQL transaction,
expiry, bad CSRF and concurrent logout. CI must run both before human merge.
Real Keycloak browser E2E, JWKS rotation, process-local rate limits and lifecycle
cleanup are now implemented; see [browser verification](../development/auth-browser-e2e.md),
[rotation verification](../development/auth-jwks-rotation-e2e.md),
[admission limits](auth-admission-limit.md) and [cleanup](auth-record-cleanup.md).
R05 closeout requires CI and review; privacy, club authorization, first-admin
bootstrap and web proxy integration remain release gates. No UI or club
functionality is added.
