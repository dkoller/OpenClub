# Real Keycloak browser authentication test

This R05 test uses the session-endpoint implementation merged in PR #65:
`GET /api/v1/auth/me` and Origin/CSRF-protected JSON `POST /api/v1/auth/logout`.
The test intentionally fails on missing endpoints; it never skips them or mocks
a successful login. A full CI pass is still required before claiming E2E success.

The `Keycloak browser authentication` workflow provisions the existing restricted
synthetic Keycloak user/client, starts PostgreSQL, builds the real API and runs
Chromium through authorization-code login, callback, session inspection, callback
replay rejection, a second SSO login, old-session rejection and logout. After
logout it resubmits the revoked cookie to prove database revocation, rather than
merely observing that the browser dropped its cookie. Contract:
[auth API](../contracts/auth-api-proposal.yaml).

The API runs directly on localhost:5173, matching the fixture's exact callback;
Keycloak runs on localhost:8180. The callback landing page can be an API 404;
no frontend content or UI is required. No auth endpoint is mocked. This verifies
the browser/BFF/Keycloak/database boundary, not React or the deployment proxy.
The existing web proxies strip `/api` while Ktor auth routes include `/api`;
that integration mismatch requires a separate runtime change before web release.

For local reproduction, use a disposable PostgreSQL and Keycloak stack, provision
the fixtures using [the fixture runbook](keycloak-e2e-fixtures.md), then start the
API with the environment shown in `.github/workflows/auth-e2e.yml` and the same
ephemeral client secret. In `dev/auth-e2e`, run:

```sh
pnpm install --frozen-lockfile
pnpm exec playwright install --with-deps chromium
pnpm test
```

Keep `OPENCLUB_E2E_USER_PASSWORD` in the test process environment. Never use real
identities. Traces, screenshots, videos and saved browser state are disabled;
do not upload server logs containing callback codes. Generated credentials are
masked in CI. Containers are stopped without deleting volumes; local operators
must explicitly manage their disposable fixture volumes.

Exhaustive invalid-token, wrong-issuer/audience and expiry cases remain verifier
and HTTP/DB test responsibilities. This does not close R05 or verify HTTPS cookie
settings, proxy routing, rate limits, key rotation or privacy release gates.
