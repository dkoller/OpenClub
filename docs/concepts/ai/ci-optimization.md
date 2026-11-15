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

For each workflow, `concurrency` cancels older, superseded runs for the **same** PR or branch, but not other parallel PRs. For the web build, pnpm dependency caching uses `apps/web/pnpm-lock.yaml`. The API uses Gradle's existing action cache and invokes `test generateJooq` together to avoid repeated Gradle initialization.

## Compose image cache and service lifetime

The Compose job uses Buildx to build and load `openclub-api:latest` and `openclub-web:latest`, then starts the stack with `--no-build`. The fixed CI-only `COMPOSE_PROJECT_NAME=openclub` makes these tags match Compose's generated image names. Local startup commands and production images are unchanged.

API and web use separate GitHub Actions cache scopes (`compose-api` and `compose-web`), with intermediate layers exported using `mode=max`. Cache misses still build the current checked-out source normally; cache export failures are non-fatal (`ignore-error=true`), so a cache outage cannot turn a successful image build into a failed check. GitHub restricts PR-created caches to that PR's merge ref; PR jobs can read the base branch cache but cannot replace it. See [Docker cache configuration](https://docs.docker.com/build/ci/github-actions/cache/) and [GitHub cache access restrictions](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache). No registry push, new credentials or additional repository write permissions are needed. Never pass secrets as Docker build arguments or copy them into build contexts: exported intermediate layers are cache data too.

Keycloak starts once before image builds, allowing its initialization to overlap build time. It remains running for both realm-import and API-backchannel checks. An unconditional final cleanup removes the combined stack's containers and network while preserving database volumes; failure logs include the optional services. All discovery, JWKS, application-health and issuer-equality assertions remain enabled.

## Measuring results

Compare GitHub Actions durations before and after using the same change categories. The baseline observed around 2026-11-14 was ~95 seconds for the API job, ~15 seconds for web, and ~105 seconds for a full Compose smoke run. The size gate alone takes a few seconds. This is a historical scaffold baseline, before the auth/backchannel checks were added. A later sampled auth-enabled Compose job took about 180 seconds, including roughly 108 seconds for build/start and an unnecessary second Keycloak startup. The current optimization adds Docker layer caching and removes that restart. Measure a first (possibly cold) run and a subsequent run of the same workflow/ref; cache transfer time can outweigh benefits for very small layers. Do not claim a fixed speedup until both are measured. More selective CI routing and artifact sharing remain deferred.

**Failure safety:** Any failure in PR changed-file enumeration fails the `changes` job; it must not silently mark a PR as passing without checks. Main-branch pushes always run the complete test suite.
