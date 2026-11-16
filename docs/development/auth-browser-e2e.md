# Real Keycloak browser authentication test

This R05 test uses the session-endpoint implementation merged in PR #65:
`GET /api/v1/auth/me` and Origin/CSRF-protected JSON `POST /api/v1/auth/logout`.
The test intentionally fails on missing endpoints; it never skips them or mocks
a successful login. The real Keycloak browser authentication workflow passed
on PR #66 (run 43634108286); the full browser journey was not run locally.

The `Keycloak browser authentication` workflow provisions the existing restricted
synthetic Keycloak user/client, starts PostgreSQL, builds the real API and runs
Chromium through authorization-code login, callback, session inspection, callback
replay rejection, a second SSO login, old-session rejection and logout. After
logout it resubmits the revoked cookie to prove database revocation, rather than
merely observing that the browser dropped its cookie. Contract:
[auth API](../contracts/auth-api-proposal.yaml).

The disposable bootstrap journey also revokes the verified administrator's
`EDIT_MEMBERS` PostgreSQL grant while the actual Keycloak session stays signed
in: directory reads still succeed, an attempted browser edit receives HTTP 403,
and the private edit controls fail closed. The test restores the grant in a
`finally` block and confirms a reload recovers access. Fixture SQL is restricted
to `OPENCLUB_E2E_DISPOSABLE=true` and executed only against the ephemeral
Compose PostgreSQL instance, never through a production HTTP endpoint.
This provides real-session revocation evidence beyond mocked 403 responses
without approving use of real personal data.

The API runs on localhost:8080, while the real Vite frontend serves
localhost:5173 and forwards /api requests (including the callback) to the API.
Keycloak runs on localhost:8180. No auth endpoint or browser UI is mocked.
The browser suite verifies both the BFF/Keycloak/database boundary and the
frontend's signed-out, signed-in-but-unlinked, four-language and logout flows,
and a restricted operator bootstrap that turns a previously unlinked verified
synthetic account into a single-club administrator
through the actual development proxy. This is not a production nginx/TLS
end-to-end test.

For local reproduction, use a disposable PostgreSQL and Keycloak stack, provision
the fixtures using [the fixture runbook](keycloak-e2e-fixtures.md), then start the
API with the environment shown in `.github/workflows/auth-e2e.yml`, port 8080
and the same ephemeral client secret. Start Vite from `apps/web` with
`pnpm install --frozen-lockfile && pnpm dev --host 127.0.0.1`, and verify
`http://localhost:5173/api/health`. In `dev/auth-e2e`, run:

```sh
pnpm install --frozen-lockfile
pnpm exec playwright install --with-deps chromium
pnpm test
```

Keep `OPENCLUB_E2E_USER_PASSWORD` in the test process environment. Never use real
identities. The bootstrap browser test is explicitly gated by `OPENCLUB_E2E_DISPOSABLE=true`
and must **never** run against an installation with data worth retaining.
The CI workflow uses the ephemeral synthetic PostgreSQL project; the test
creates its single club through the existing restricted Gradle operator task,
not an unapproved browser endpoint. A local rerun on a previously initialized
database cannot perform bootstrap again and must start with a separate fresh
synthetic project. Do not force-reset an installation to make the test pass.

Traces, screenshots, videos and saved browser state are disabled;
do not upload server logs containing callback codes. Generated credentials are
masked in CI. Containers are stopped without deleting volumes; local operators
must explicitly manage their disposable fixture volumes.

Exhaustive invalid-token, wrong-issuer/audience and expiry cases remain verifier
and HTTP/DB test responsibilities. The separate [real rotation test](auth-jwks-rotation-e2e.md)
verifies signing-key change without API restart; [admission limits](../security/auth-admission-limit.md)
and [cleanup](../security/auth-record-cleanup.md) have their own regression tests.
This test does not verify deployment HTTPS cookie settings, production nginx
routing or privacy/authorization/first-admin release gates. R05 closeout still requires review.
