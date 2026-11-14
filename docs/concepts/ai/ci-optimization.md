# CI execution and path classification

OpenClub uses a PR-size gate on every pull request (maximum **999** added + deleted lines) and selective application checks to reduce CI time for parallel contributors.

## Pull-request behavior

| Changed paths | API tests + jOOQ | Web tests/build | Compose smoke |
| --- | --- | --- | --- |
| `docs/**`, `README.md`, agent instructions, PR template | Skip | Skip | Skip |
| `docs/contracts/**` (API contract) | Run | Run | Skip |
| `apps/api/**` | Run | Skip | Skip |
| `apps/web/**` | Skip | Run | Skip |
| API or web Dockerfiles / `.dockerignore`, `apps/web/nginx.conf` | Run for API / web as applicable | Run for web as applicable | Run |
| `compose*.yaml`, Compose workflow | Skip | Skip | Run |
| Unknown/new paths | Run | Run | Run |

On pushes to `main`, **all** application jobs run. Workflow changes targeting the build workflow run both language checks. A pull request changing both API and web runs both. **Jobs remain defined and report skipped success** when they do not apply; the lightweight `changes` job checks the actual PR file list. Ensure repository branch protection treats skipped jobs as acceptable while requiring the PR-size gate and change-detection jobs.

The path classifier is duplicated in the build and smoke workflows to keep each workflow independently checkable; update **both** when adding new source directories. Classify unfamiliar paths conservatively (run all checks). This is an intentional first optimization, not an excuse to weaken full-stack checks on `main`.

## Cancellation and caching

For each workflow, `concurrency` cancels older, superseded runs for the **same** PR or branch, but not other parallel PRs. For the web build, pnpm dependency caching uses `apps/web/pnpm-lock.yaml`. The API uses Gradle's existing action cache and invokes `test generateJooq` together to avoid repeated Gradle initialization.

## Measuring results

Compare GitHub Actions durations before and after using the same change categories. The baseline observed around 2026-11-14 was ~95 seconds for the API job, ~15 seconds for web, and ~105 seconds for a full Compose smoke run. The size gate alone takes a few seconds. Docker layer caching and more sophisticated CI routing are deliberately deferred until the first optimization is measured.

**Failure safety:** Any failure in PR changed-file enumeration fails the `changes` job; it must not silently mark a PR as passing without checks. Main-branch pushes always run the complete test suite.
