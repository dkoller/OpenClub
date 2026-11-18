# Real Keycloak browser authentication test

**Additional M1 keyboard/locale acceptance (2026-11-17):** The existing synthetic Keycloak/Chromium administrator journey now checks keyboard-only member search in DE/EN/FR/IT (localized accessible name, Tab focus order and Enter submission) and keyboard cancellation of the named per-member archive confirmation. This is focused browser evidence, not a full WCAG or assistive-technology audit; no real member data is used.

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
and the private edit controls fail closed. A separate live
`VIEW_MEMBER_DIRECTORY` revocation denies authorized name searches and hides
previously rendered private names/controls without affecting the approved
anonymous display-name endpoint. Each fixture restores its grant in a
`finally` block and confirms a reload recovers access. Fixture SQL is restricted
to `OPENCLUB_E2E_DISPOSABLE=true` and executed only against the ephemeral
Compose PostgreSQL instance, never through a production HTTP endpoint.
This provides real-session revocation evidence beyond mocked 403 responses
without approving use of real personal data.

Two further real-database steps close the denied-role and cross-team gap (#25),
again without injected responses. First, the administrator's club grants are all
removed while the Keycloak session stays signed in: an `ADMIN` role alone must
grant nothing, so the member list shows its denied alert, the teams and
permission regions disappear, member, team and grant reads return HTTP 403, the
role search and club-name save are hidden by their first denied request, while
`GET /api/v1/club` and the anonymous directory keep working. Each reload waits
for the real 403 response before asserting, so no check passes before the page
has settled. Second, a single team-scoped `VIEW_TEAMS` grant must show exactly
that team in the browser and in `GET /api/v1/teams`, and the roster of every
other team must answer 403. The step records the administrator's original club
grants first, restores them in a `finally` block, and reloads to confirm access
returns. This is targeted denied-role and cross-team browser evidence, not a
full assistive-technology audit.

Two further steps cover invalid input and machine-checkable accessibility (#25).
Invalid input: typing into a name field stops at its 200-character `maxLength`;
a malformed email is blocked by the browser's own validation before any request;
a future birth date and a whitespace-only member name pass the browser but the
server answers `400` and the member panel shows its announced `Action failed.`
alert with no member created; a duplicate (case- and padding-insensitive) or
whitespace-only team name is rejected with the `Could not create team.` alert and
an unchanged team list; the adult-age field leaves Save disabled for values below,
above or between whole numbers in the 13-25 range. Accessibility: one `main`
landmark and one `h1`; every private panel is a named region; the document `lang`
attribute follows the selected language; every visible control has an accessible
name in English and French; and every `aria-describedby`/`aria-labelledby`
reference resolves to an existing element. These are structural checks only.
Screen-reader behaviour, focus order and contrast beyond the keyboard-only member
search and archive cancel (#303) still need a manual audit and remain open.

The member-management browser journey also seeds 25 fictional members into the
disposable PostgreSQL fixture, loads the first 20 through the real authorized
search API, and simulates a network failure only for the next page. The UI
retains the first page, disables duplicate paging while failing/loading,
retries the **same opaque cursor**, and appends the remaining five exactly
once. A subsequent new search replaces the results; all temporary fixtures
are cleaned up. No new member fields or HTTP routes are exposed.

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

CI installs Chromium with `dev/auth-e2e/install-chromium.sh` instead. It first downloads only the browser binary (up to three attempts, 120 s each) and checks that it launches; GitHub's Ubuntu runners already carry Chromium's shared libraries, and the optional font and Mesa packages that `--with-deps` adds come from an Ubuntu mirror that occasionally crawls or stalls. Only if the browser cannot launch does it fall back to `playwright install --with-deps chromium`, with bounded `apt` connections, a 100 s deadline per attempt, up to three attempts, and cleanup of any leftover `apt-get` and the `dpkg` lock between attempts. Use the commands above locally.

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
