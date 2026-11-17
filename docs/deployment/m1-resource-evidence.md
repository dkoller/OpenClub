# Synthetic M1 deployment evidence (operator checklist)

**Disposable restart evidence (2026-11-17):** A guarded Compose CI step now inserts one fictional club into the real Flyway schema only when the CI database has no club, restarts PostgreSQL followed by API/web, checks `/api/ready`, verifies the same club UUID persisted and deletes the fixture. It requires the exact disposable GitHub Actions project and a separate confirmation token; **never run it on a real club**. This demonstrates restart persistence, not encrypted backups, a verified signed-in workflow, host-level 4 GB sizing or external hosting acceptance.

**Not a hosting claim or a real-data release.** This page is the evidence sheet for
[#27](https://github.com/dkoller/OpenClub/issues/27) and
[#28](https://github.com/dkoller/OpenClub/issues/28). CI exercises disposable
PostgreSQL and OIDC/Chromium workflows, but cannot declare an operator-hosted
external HTTPS installation or a reliable 4 GB production footprint.

## 1. Obtain an approved synthetic host

Record privately: operator, date, host/DNS name, provider selected
(**Keycloak OR Rauthy**, not both for one OpenClub installation), architecture
and VM/container limits. Never include personal member records, admin/client
secrets, cookies, generated recovery tokens, unmasked OIDC callbacks or private
backups in GitHub issues or workflow artifacts.

Use the [HTTPS demo Compose overlay](single-club.md#opt-in-public-https-reference-deployment-synthetic-data-only).
Keep the bootstrap and all test data synthetic. The optional Rauthy
`LOCAL_TEST` fixture is **not** a production/public provider configuration.
For Rauthy, the provider may advertise an issuer like
`https://id.example/auth/v1/` while publishing discovery at
`https://id.example/.well-known/openid-configuration`. The external probe
tries issuer-relative discovery, then the issuer **origin root only on HTTP 404**,
and still requires the exact issuer value and valid TLS. Keycloak continues
using its usual issuer-relative discovery.

## 2. Read-only resource observations

From the repository root, on the **synthetic** Compose host after the API and
web are running, select the actual Compose project name and execute:

```sh
COMPOSE_PROJECT_NAME=openclub-m1-demo \
  bash dev/measure-synthetic-compose.sh --synthetic-only
```

The script refuses an implicit project name, requires running Compose
`postgres`, `api` and `web` service labels belonging to that project,
requires a PostgreSQL named volume, and prints three sampled Docker
CPU/memory readings. It does **not** restart containers, inspect data
or credentials, provision hosts, create backups or publish artifacts.
The CI Compose smoke runs it against its own synthetic stack.

**Important limitations:** Docker stats reports container usage at each
instant, not maximum process resident memory, memory used by the Docker
daemon/kernel/host, resource pressure during cold starts, or a proof that the
entire deployment fits within 4 GB. The host's identity provider, reverse
proxy, OS, disk and backups also consume resources. Repeat under documented
cold-start and full four-language browser workflow load; separately record
host-level peak memory, swap, disk capacity and available headroom. A
successful CI sample alone must **not** be described as 4 GB acceptance.

## 3. Reproduce acceptance on the actual host

Use the credential-free read-only HTTPS probe with the exact external origin
and provider issuer:

```sh
OPENCLUB_DEMO_ORIGIN=https://club.example.org \
OPENCLUB_OIDC_ISSUER=https://id.example.org/realms/openclub \
  bash dev/verify-synthetic-https-demo.sh --synthetic-only
```

It validates TLS, HTTPS security headers, database readiness, anonymous API
denial, exact OIDC discovery issuer and public JWKS. Never use `--insecure`
or reuse the disposable localhost Rauthy test certificates externally.
Record probe result and date **without** response bodies or sensitive logs.

Then manually witness real OIDC login, bootstrap, synthetic member
create/search/edit/archive, team assignment, role/grant/revoke, session
expiration/denial and DE/EN/FR/IT UI. Stop/start API and web without
deleting volumes; verify the synthetic member/team data persists. Verify
provider state and login after an independently planned provider restart.
Record the backup/restore rehearsal separately; a restart does not
prove backup recoverability.

## 4. Acceptance record (no secrets)

| Evidence | Actual observed result / link / date |
| --- | --- |
| Approved operator and synthetic-only host | **Pending** |
| TLS issuer and browser checks (provider noted) | **Pending** |
| External probe success, exact origin and date | **Pending** |
| Member/team/grant, DE/EN/FR/IT walkthrough | **Pending** |
| API/web + provider restart/persistence | **Pending** |
| Host RAM peak, Docker samples, disk/swap and load conditions | **Pending** |
| 4 GB sizing decision and monthly hosting cost | **Pending** |
| Limitations and independent M1 acceptance | **Pending** |

Do not check off M1 in the roadmap or #28 until actual operator-controlled
external evidence exists. M2 requires separate privacy/legal, retention,
export/erasure/audit, witnessed recovery and production release approval.
