# CI execution and path classification

OpenClub uses a PR-size gate on every pull request (maximum **5,000** added + deleted lines) and selective application checks to reduce CI time for parallel contributors.

## Pull-request behavior

| Changed paths | API tests + jOOQ | Web tests/build | Compose smoke |
| --- | --- | --- | --- |
| `docs/**`, `README.md`, `.gitignore`, agent instructions, PR template | Skip | Skip | Skip |
| `docs/contracts/**` (OpenAPI only) | Skip; OpenAPI validator runs | Skip | Skip |
| `apps/api/**` | Run | Skip | Skip |
| `apps/web/**` | Skip | Run | Skip |
| API or web Dockerfiles / `.dockerignore`, `apps/web/nginx.conf` | Run for API / web as applicable | Run for web as applicable | Run |
| `compose*.yaml`, Compose workflow | Skip | Skip | Run |
| Unknown/new paths | Run | Run | Run |

The OpenAPI contract validation job runs on **every PR regardless of changed files**, including contract-only changes. The current backend and web have no generated DTO/client from these discussion drafts; contract-only PRs do not need to rebuild them. When generated clients or executable schema tests are introduced, update this routing and the affected CI checks in the same PR.\n\nOn pushes to `main`, **all** application jobs run. Workflow changes targeting the build workflow run both language checks. A pull request changing both API and web runs both. **Jobs remain defined and report skipped success** when they do not apply; the lightweight `changes` job checks the actual PR file list. Ensure repository branch protection treats skipped jobs as acceptable while requiring the PR-size gate and change-detection jobs.

A root `.gitignore`-only PR does not change application source or Docker build-context exclusions (`.dockerignore` is handled separately), so it should not consume API/web/Compose runner minutes. A PR that also changes source/config still runs the corresponding checks. Pushes to `main` retain the full suite.

The path classifier is duplicated in the build and smoke workflows to keep each workflow independently checkable; update **both** when adding new source directories. Classify unfamiliar paths conservatively (run all checks). This is an intentional first optimization, not an excuse to weaken full-stack checks on `main`.

## Browser authentication workflow scope

The expensive `Keycloak browser authentication` workflow runs for application API changes, browser fixtures, Keycloak fixtures and changes to its workflow. API unit/integration **test-only** changes under `apps/api/src/test/**`, and API contributor instructions in `apps/api/AGENTS.md`, do not rerun Chromium or Keycloak: the API build/test workflow still runs PostgreSQL tests. A mixed PR that also changes runtime API code still triggers the browser check. The workflow remains manually dispatchable. This saves unnecessary free-tier Actions minutes without excluding production authentication, club authorization, migration, build or configuration changes. Treat changes to test-runner dependencies and auth browser fixtures conservatively: the API root and `dev/auth-e2e/**` still trigger it. If branch protection requires a check that GitHub leaves pending when path-skipped, adjust the branch-rule configuration or use a continuously reported classifier; do not bypass protection.

## Cancellation and caching

For each workflow, `concurrency` cancels older, superseded runs for the **same** PR or branch, but not other parallel PRs. For the web build, pnpm dependency caching uses `apps/web/pnpm-lock.yaml`. The API uses Gradle's existing action cache and runs `./gradlew test` (jOOQ code generation was removed on 2026-11-27, ADR-0001).

## Compose image cache and service lifetime

The Compose job uses Buildx to build and load `openclub-api:latest` and `openclub-web:latest`, then starts the stack with `--no-build`. The fixed CI-only `COMPOSE_PROJECT_NAME=openclub` makes these tags match Compose's generated image names. Local startup commands and production images are unchanged.

API and web use separate GitHub Actions cache scopes (`compose-api` and `compose-web`), with intermediate layers exported using `mode=max`. Cache misses still build the current checked-out source normally; cache export failures are non-fatal (`ignore-error=true`), so a cache outage cannot turn a successful image build into a failed check. GitHub restricts PR-created caches to that PR's merge ref; PR jobs can read the base branch cache but cannot replace it. See [Docker cache configuration](https://docs.docker.com/build/ci/github-actions/cache/) and [GitHub cache access restrictions](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache). No registry push, new credentials or additional repository write permissions are needed. Never pass secrets as Docker build arguments or copy them into build contexts: exported intermediate layers are cache data too.

Keycloak starts once before image builds, allowing its initialization to overlap build time. It remains running for both realm-import and API-backchannel checks. An unconditional final cleanup removes the combined stack's containers and network while preserving database volumes; failure logs include the optional services. All discovery, JWKS, application-health and issuer-equality assertions remain enabled.

## Measuring results

Compare GitHub Actions durations before and after using the same change categories. The baseline observed around 2026-11-14 was ~95 seconds for the API job, ~15 seconds for web, and ~105 seconds for a full Compose smoke run. The size gate alone takes a few seconds. This is a historical scaffold baseline, before the auth/backchannel checks were added. A later sampled auth-enabled Compose job took about 180 seconds, including roughly 108 seconds for build/start and an unnecessary second Keycloak startup. The current optimization adds Docker layer caching and removes that restart. Measure a first (possibly cold) run and a subsequent run of the same workflow/ref; cache transfer time can outweigh benefits for very small layers. Do not claim a fixed speedup until both are measured. More selective CI routing and artifact sharing remain deferred.

**Failure safety:** Any failure in PR changed-file enumeration fails the `changes` job; it must not silently mark a PR as passing without checks. Main-branch pushes always run the complete test suite.

## 2026-11-17 measured follow-up: critical-path browser setup

The recorded job logs for PRs #285/#286/#287 show, respectively, Keycloak browser
jobs taking **140/146/151s**, Rauthy browser **104/127/97s**, Compose
**98/82/95s**, and the API build job **95/90/86s** (approximate elapsed
job-log times, not scheduling latency). The PR #287 Keycloak step combining
`pnpm install`, `playwright install --with-deps chromium`, and four tests
ran for **66s**; Playwright reported **31.9s** of tests and the remainder
was setup. Chromium binaries downloaded in about **5s**, while Linux package
installation accounted for much of the setup. An additional browser cache
would need to outperform its own transfer overhead; **no cache has been added**.

The Keycloak and Rauthy browser workflows now start the *same* pinned
Playwright/Chromium install immediately after Node setup, in the background
during provider initialization and JVM compilation. A status file on the
disposable runner is checked before tests; install failures still fail the
workflow, and no browser tests are omitted. The temporary installation logs
are not uploaded. This changes timing only; browser/OS dependencies and exact
test commands remain unchanged. Compare the **complete browser job elapsed
time**, not merely the shortened final test step, across several post-change
runs before claiming improvement. This is a controlled experiment; revert if
resource contention or reliability outweighs overlap.

### Other examined opportunities, deferred for safety

- **Gradle/jOOQ:** PR #287 ran `./gradlew test generateJooq --no-daemon`
  in approximately **69s**. Generation began at 04:40:02 and compilation
  at 04:40:18 (about **16s**), but generated Java sources are declared as
  an input to Kotlin application compilation. No evidence yet supports
  skipping generation based solely on PR file paths. Profiling task
  up-to-date/cache inputs would be required before changing it.
- **Queue and cancellation:** The existing concurrency groups cancel only
  superseded runs on the same PR/ref. The timestamped job logs measure
  execution but do not prove a material scheduling delay or duplicate run
  cost; keep cancellation enabled until run-created, started and completed
  timestamps show a reproducible problem.
- **Shared build artifacts:** API tests and the two provider browser jobs
  each compile independently, but already run in parallel and verify
  different runtime configurations. Centralizing the build with
  `needs`/cross-workflow artifact downloads would introduce a dependency
  before browser tests and could worsen merge latency. No such dependency
  has been added without an A/B critical-path measurement.

These jobs are deliberately all relevant on shared API/authentication
changes. Compose verifies actual image/overlay behavior, while both provider
browser jobs verify distinct OIDC security journeys. None is removed or
moved to main-only.

## 2026-11-17 browser critical path, Gradle cache and trigger alignment

**Measured before the change** (100 completed PR runs on 2026-11-17, plus step timings for PR #314): no run waited in a queue and none was retried. A PR's checks finished in about 2.5 minutes. The longest jobs were Keycloak browser (~147s) and Rauthy browser (~146s). In both, the API `installDist` build (~36–38s) ran only *after* provider startup (~31–40s), although it depends on neither the provider nor the fixtures. The API job spent ~68s in `./gradlew test generateJooq`.

Changes:

- **Overlapped API build (Rauthy only; Keycloak reverted).** #315 started `./gradlew installDist` in the background before provider startup in both browser workflows, using the same status-file pattern as the Chromium install. The step that launches the API waits for a `success` status (up to 300s) and otherwise fails, printing the build log tail. Build output contains no credentials. In the Rauthy job the build now runs before the test-only `JAVA_TOOL_OPTIONS` truststore is set, so Gradle uses the runner's default trust store; the truststore still applies to the API at runtime. **The Keycloak workflow was returned to the sequential build** after measurement (see below).
- **Gradle build cache.** `apps/api/gradle.properties` enables `org.gradle.caching`. `setup-gradle` persists the cache (written by `main`, read-only on PRs), so unchanged compilation can be restored instead of recompiled. The `test` task opts out (`outputs.cacheIf { false }`) because the PostgreSQL service is not a task input: integration tests always execute. Verified locally: after `clean`, `compileKotlin` and `compileTestKotlin` came `FROM-CACHE` and `test` ran.
- **Browser triggers.** Both browser workflows skip PRs whose only web changes are Vitest unit-test files (`apps/web/src/**/*.test.ts`), which do not ship in the bundle. The Rauthy workflow previously did **not** run for API Gradle build/dependency changes (`build.gradle.kts`, wrapper, properties), web dependency/Vite config changes, its own `rauthy.playwright.config.js`, or the shared Playwright manifest/lockfile. It now matches the Keycloak triggers for those paths. Runtime API, web, provider and workflow changes still run both suites.

**Measured result (PR #315, first run).** Neither browser job got faster:

- **Keycloak: 153s** (was ~147s). Keycloak startup slowed from ~40s to 55s while Gradle and the Chromium install competed for the runner's 4 vCPUs. The build still had not finished when Keycloak was ready, so the job then waited another 30s. The overlap is reverted for Keycloak.
- **Rauthy: 150s** (was ~146s). Launching the API dropped from ~36s to 2s, but the following "require actual provider-generated signing-key rotation" step rose from 0s to 49s. The test rotation runs on a once-per-minute schedule, and the sequential build used to hide most of the wait for the next minute boundary. The overlap is kept: it never adds time, and its expected average saving is small (~10s).

The Gradle cache had no effect on that run because the cache is only written by `main`. Re-measure after several PRs. The rotation wait in the Rauthy job is now its main fixed cost. Shortening it would mean changing the test rotation schedule, which needs a separate review of what the test proves.

## 2026-11-26 failure review: what fails, and why

Runs since 2026-11-19 (660 runs, excluding cancelled ones replaced by a newer push): *Keycloak browser
authentication* failed 15 of 106 times, *Rauthy browser authentication* 7 of 95, *Build and test* 5 of 135,
*Compose smoke test* 2 of 134, *Documentation screenshots* 3 of 22; the size, contract and drill checks never failed.
Most failures were real: a feature branch whose end-to-end test had not yet followed a UI change, or a compile or
test error. Those are the checks doing their job. These were not, and are fixed:

| Cause | Seen | Fix |
| --- | --- | --- |
| Rauthy OTP test asked the server right after *clicking* Sign out, before the sign-out finished (`/auth/me` 200, not 401) | 3, once on `main` | wait for *Sign in* to appear first |
| Rauthy passkey test navigated while the sign-out navigation was running (`net::ERR_ABORTED`) | 1, on `main` | same wait |
| Runs between 23:00 and 01:00 UTC: the runner (UTC) and the club (Zurich) disagreed about "today" | 1, on `main` | `TZ` and the browser's `timezoneId` set to `Europe/Zurich` in both Playwright configs |
| `openForm` checked once for a button that was still rendering, skipped it, and the next field waited out the 180 s timeout | 2 | wait up to 3 s for the button |
| Maven Central dropped a request (`Could not get resource …qrbill-generator…pom`) | 2, once on `main` | Gradle retries downloads 6 times with a 2 s initial pause (`apps/api/gradle.properties`) |

Not fixed: one `pnpm/action-setup` self-install failed on a network error (exit 236, all three jobs of one push at
once). It happened once; a re-run is the remedy. The long Keycloak journey (`club-landing.spec.js`, one 1,500-line test
in which one failure skipped everything after it) was split on 2026-11-27 into 29 tests per area that each create
their own data; see [browser e2e](../../development/auth-browser-e2e.md).

## 2026-11-27 follow-up

- **Picture pull requests stay green:** the screenshot workflow's commit carries `[skip ci]`. Without it, the size and
  Compose checks started on the pull request the workflow opened and failed at once with no job ("workflow file
  issue", 3 times); the workflow already refuses to merge anything but the pictures and `SOURCE`.
- **Browser failures show the page:** when a Keycloak or Rauthy browser test fails, the job prints Playwright's
  `error-context.md` for each failed test, the page's accessibility tree as text (synthetic data only; no cookies,
  tokens, traces or network logs, and nothing is uploaded). Two runs this week went to guessing what the page showed.
- **Not changed:** on pull requests the Gradle cache is already read-only (`setup-gradle` default; its post step takes
  1 s). Building the API while Keycloak starts would save about 20 s but makes both compete for the runner, which
  could bring back timeouts at readiness; it was left out.
