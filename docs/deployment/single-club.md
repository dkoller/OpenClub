# Operating one OpenClub club per installation

**Decision:** 2026-11-15. **Current operations status 2026-11-17 (through merged #298):** One-club synthetic pre-production application with local Compose, optional Caddy HTTPS/OIDC reference configuration, and browser/API CI. **No operator-hosted external TLS/OIDC demo or live-member-data authorization exists.**

**M1 synthetic deployment evidence:** [Resource sampling and external acceptance checklist](m1-resource-evidence.md) distinguishes read-only three-sample CI resource observations from host-level peak usage, 4 GB sizing and the still-unperformed real external HTTPS/OIDC/browser demonstration. The external read-only probe supports either Keycloak issuer-relative discovery or Rauthy origin-root discovery on 404, always with valid TLS and exact issuer verification. No hosting, live-data or production approval is implied.

Each club runs an independent application process (API + web), PostgreSQL database, application configuration and backups. The same MIT-licensed source can serve any club; never share a PostgreSQL database or schema, session cookie secret, OIDC client configuration or member records across installations.

## Start for development

From the repository root, using a local Docker installation and a unique local database password in a **gitignored** environment file:

```sh
docker compose up -d --build
docker compose ps
curl --fail http://localhost:5173/api/health
```

**CI deployment proof (synthetic only):** The Compose smoke workflow now verifies `/api/ready` through nginx with PostgreSQL online, then stops the disposable database after normal OIDC checks and requires readiness 503 while liveness remains 200. This is an outage regression check, **not** evidence of production monitoring, external TLS, high availability, backups, or a real-club release.

**Readiness vs liveness:** `/api/health` and `/health` intentionally return process liveness only. Use `curl --fail http://localhost:5173/api/ready` to verify the API can execute a PostgreSQL query (200 `{"status":"ready"}`; missing/disconnected DB returns 503 `{"status":"unavailable"}`). Readiness has no authenticated member information and uses `Cache-Control: no-store`. It does **not** prove Keycloak/OIDC availability, correct HTTPS configuration, backup recovery, or production readiness; verify those independently for M1/M2.

The basic stack starts without authentication. OIDC is opt-in; without it there are **no member administration endpoints enabled**. To configure a local test OIDC provider, follow [Keycloak development setup](../development/keycloak-local.md). The nginx/Vite proxies preserve `/api` so Ktor routes remain reachable.

**First-club setup:** an operator-only bootstrap command now creates the sole club and first ADMIN member atomically. Flyway V9 also enforces a maximum **raw** club display-name length of 200 characters for direct SQL changes, including whitespace padding (the original nonblank rule still applies). It is **not an HTTP route**. No second club can be created. The restricted command is implemented under closed [R07/#16](https://github.com/dkoller/OpenClub/issues/16); human-reviewed recovery drills and operator sign-off remain open in [#180](https://github.com/dkoller/OpenClub/issues/180). Use **synthetic data only** until privacy and production requirements are approved.

## Operator-only first-club bootstrap (pre-production)

1. Configure local OIDC and **sign in once** as the intended first administrator. The verified callback creates an unlinked account, not a member or admin.
2. Using restricted database operator access, obtain the local account UUID by looking up its **verified issuer + subject** in `account_identities` (joining `accounts` if necessary). Never identify a person by email alone, and never accept a client-supplied account ID without independent operator verification.
3. On a restricted operator shell with `DB_URL`, `DB_USER`, and `DB_PASSWORD` set, supply the following bootstrap environment variables. Do not put real credentials or personal details in the repository, shell history, or CI logs.

| Variable | Meaning |
| --- | --- |
| `OPENCLUB_BOOTSTRAP_CONFIRM` | Literal `create-first-club` |
| `OPENCLUB_BOOTSTRAP_ACCOUNT_ID` | UUID of that previously verified local account |
| `OPENCLUB_BOOTSTRAP_CLUB_NAME` | Club display name |
| `OPENCLUB_BOOTSTRAP_CLUB_SLUG` | Unique lowercase slug |
| `OPENCLUB_BOOTSTRAP_FIRST_NAME`, `OPENCLUB_BOOTSTRAP_LAST_NAME` | First administrator's local member names |
| `OPENCLUB_BOOTSTRAP_BIRTH_DATE` | ISO date `YYYY-MM-DD` |
| `OPENCLUB_BOOTSTRAP_EMAIL`, `OPENCLUB_BOOTSTRAP_PHONE` | First administrator's contact fields |

From `apps/api`, run `./gradlew bootstrapFirstClub` (Windows: `gradlew.bat bootstrapFirstClub`). The task uses the ordinary application dependencies and configured PostgreSQL database. It fails closed if the club or any local members already exist, the selected account lacks a verified identity binding, or required fields fail validation. A transaction-level advisory lock serializes concurrent bootstrap attempts; the database singleton constraint remains a second line of defense. The command creates no OIDC credentials, browser session, or web-exposed admin registration endpoint. The operator bootstrap atomically gives the verified first ADMIN explicit `MANAGE_PERMISSIONS`, `VIEW_MEMBER_DIRECTORY` and `EDIT_MEMBERS` club grants for the initial synthetic M1 workflow; it does not grant those rights to other accounts or administrators. Upgrading an already initialized pre-production installation does **not** auto-grant permission-management rights: handle that exceptional provisioning through a separately reviewed operator procedure, never through unrestricted web setup.

## Operator-only recovery of the sole administrator

**Required human controls:** follow the [administrator identity-verification, independent-approval, audit and witnessed-drill procedure](../security/admin-recovery-procedure.md) and record each action using its [private case template](../security/admin-recovery-case-template.md). The CLI enforces technical database/session conditions only; it does not replace these controls or constitute M2 sign-off.

**Emergency only, not self-service.** This recovery command changes the account attached to the existing sole `ADMIN` member; it does not create members, promote roles, or change the club. It is not registered as an HTTP route. The operator's database credentials are the privileged recovery boundary; the CLI cannot independently prove the human's entitlement.

Before executing: (1) confirm the old administrator's login is genuinely inaccessible; (2) obtain and verify written authorization from the club's designated responsible person and an independent reviewer, and keep the decision in a restricted incident record; (3) take and **verify** a recoverable database backup; (4) authenticate the replacement identity through the configured OIDC provider and independently check its issuer + subject and account UUID in `account_identities`; (5) verify the expected club ID, sole administrator member ID, and previous account ID from the database. **Never select accounts by email/name alone.** If the rightful owner is disputed, multiple administrators exist, or independent verification is impossible, stop and escalate instead of guessing.

On a restricted operator shell with the same `DB_URL`, `DB_USER` and `DB_PASSWORD` as the installation, provide:

| Variable | Meaning |
| --- | --- |
| `OPENCLUB_RECOVERY_CONFIRM` | Literal `rebind-sole-admin` |
| `OPENCLUB_RECOVERY_CLUB_ID` | Expected installed club UUID |
| `OPENCLUB_RECOVERY_MEMBER_ID` | Existing sole ADMIN member UUID |
| `OPENCLUB_RECOVERY_OLD_ACCOUNT_ID` | Account currently linked to that member |
| `OPENCLUB_RECOVERY_NEW_ACCOUNT_ID` | Different, verified OIDC account not linked to any member |

Run `./gradlew recoverSoleAdmin` from `apps/api` (Windows: `gradlew.bat recoverSoleAdmin`). Do not put private IDs, credentials or approval evidence in committed files, command-line arguments, CI job output or shared logs. The command locks and verifies the one club and sole-admin member, requires a verified replacement identity held stable through recovery, rejects linked replacements, changes the member account association atomically, and **revokes existing sessions for both the old and replacement accounts in the same transaction**. This prevents an already signed-in replacement account from inheriting administrator privileges without a new login. A retry with stale IDs fails; concurrent recoveries and protected role/grant mutations are serialized so the sole-admin condition cannot change during recovery. A failed command leaves all access unchanged.

Afterward, require the replacement administrator to sign in again and verify that the new session can access the club and that the old account cannot access club resources, record the outcome in the private incident log, and rotate/revoke the old provider identity if compromised. Do not delete the previous OIDC account or the existing club/member record as a shortcut. If there is no existing administrator member, more than one administrator, no trusted replacement identity, or a database inconsistency, this narrow recovery procedure intentionally refuses to act. Any broader manual repair requires a separate reviewed decision and backup. **This is not privacy/legal approval or readiness for real personal data.**

## Fresh database only — schema reset

Merged PR #79 replaces the previous Flyway V1–V4 chain with a fresh `V1__single_club.sql`. It intentionally does **not** migrate legacy database volumes. Existing Flyway history must fail rather than silently merge/delete old tenant data.

**Destructive local-only reset:** stop a disposable development Compose stack and explicitly remove its disposable PostgreSQL volume *only after confirming there is no data to keep and that a backup is unnecessary*. Never run volume deletion against a shared or production installation. You can create a new named Compose project instead:

```sh
COMPOSE_PROJECT_NAME=openclub_single_club_test docker compose up -d --build
```

Keep separate project names/volumes per club. Do not point two installations at the same `DB_URL`, database or schema. The singleton database constraint does not by itself enforce physical isolation if operators intentionally reuse credentials.

## Browser-facing HTTP response headers (synthetic M1)

The bundled nginx proxy now sets `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and
`Permissions-Policy: camera=(), microphone=(), geolocation=()`, and a
same-origin `Content-Security-Policy` that denies framing, object embedding, external
scripts/styles/API connections and base-tag injection. The policy allows same-origin
compiled assets and API requests, with data: images; OIDC sign-in uses top-level
navigation to the independently hosted identity provider rather than cross-origin
fetches. The Compose smoke test checks headers on the static shell, proxied API and
error responses. These are browser defense-in-depth, **not** authorization checks,
HTTPS transport, secure cookie configuration, or real-data readiness. The current default Compose port remains
loopback-only HTTP and Keycloak is still a development identity provider. A real
TLS/OIDC ingress, external deployment and browser/security acceptance are still
required before M1 can be marked deployed.

## Opt-in public HTTPS reference deployment (synthetic data only)

`compose.https.demo.yaml` is an **optional** reference overlay for a host that an
operator already controls. It is not used by the local development command,
does not provision hosting/DNS, and does not constitute a deployed M1 demo.
The overlay uses the pinned open-source Caddy `2.11.9-alpine` image as an
ACME TLS reverse proxy to the existing nginx/web and Kotlin API; only
ports **80 and 443** are published. The default HTTP web and PostgreSQL host
ports are reset to empty in this overlay. Caddy stores certificate and
configuration state in separate persistent volumes. The reference ingress adds
`Strict-Transport-Security: max-age=86400` (one day, no `includeSubDomains` or
preload), only on the public HTTPS hostname, avoiding long-term browser pinning
of an experimental club domain. The read-only external probe requires an HSTS
age of at least one day; locally served HTTP development remains unaffected.

Before attempting it, the operator must arrange **all** of the following:
a host with public ports 80/443 and DNS pointing to it; an **independent
HTTPS OIDC provider** with a confidential client and *exact* callback
`https://<demo-host>/api/v1/auth/callback`; a fresh disposable/synthetic
Compose project and a unique database password; and an agreed way to
securely supply the required client secret. Do **not** use the
`compose.auth.dev.yaml` Keycloak start-dev/embedded database with a public
demo. Never expose real member data or use an existing production volume.

Supply the variables through a restricted operator environment or gitignored
`.env`, without printing secret-bearing `docker compose config` output:

```sh
# In the operator shell, set a unique COMPOSE_PROJECT_NAME and all required
# POSTGRES_PASSWORD, OPENCLUB_DEMO_HOST, OPENCLUB_OIDC_ISSUER,
# OPENCLUB_OIDC_AUTHORIZATION_ENDPOINT, OPENCLUB_OIDC_CLIENT_ID and
# OPENCLUB_OIDC_CLIENT_SECRET variables first. No values are checked in.
docker compose -f compose.yaml -f compose.https.demo.yaml config --quiet
docker compose -f compose.yaml -f compose.https.demo.yaml up -d --build --wait
```

The API runs `OPENCLUB_AUTH_MODE=production`, with an HTTPS origin and
callback built from the operator-supplied demo hostname. Missing OIDC secrets
or an unreachable/mismatched provider must fail startup. Caddy obtains and
renews publicly trusted certificates when DNS and ACME reachability are
correct. A new, empty installation still requires the verified-identity,
operator-only first-club bootstrap; login itself grants no club rights.

For read-only external TLS/OIDC verification, use
[`dev/verify-synthetic-https-demo.sh`](../../dev/verify-synthetic-https-demo.sh)
and the example below. Then conduct the **real** DE/EN/FR/IT synthetic member,
team, permission and negative browser workflows and restart/persistence
checks, recording evidence in [#28](https://github.com/dkoller/OpenClub/issues/28).
The overlay is not a production hardening recipe: app/database secrets are
in container environment, and unattended backups, provider operations,
monitoring, abuse limits, incident procedures and M2 privacy/legal decisions
remain unresolved. Public demo costs depend on operator hosting and DNS;
no free public host is supplied or assumed.

To stop the reference environment while keeping PostgreSQL and TLS
certificate volumes, use `docker compose -f compose.yaml -f
compose.https.demo.yaml down` with the same project name. **Never** run
`down -v` against a project containing anything you need to retain.

## Authentication, privacy and access

- Sessions use the installation's verified OIDC issuer and subject, an opaque HttpOnly cookie and server-side PostgreSQL session state.
- Signing in does not automatically give any club/member access. `GET /api/v1/club` only responds to an authenticated account linked to a local member. Missing membership returns 403; missing session returns 401.
- Local member first/last name and DOB are required, both email and phone for adults, and gender only where required for players by local club policy. Adult threshold, safe member API validation and minors' visibility require explicit R03/R04 completion.
- Club/team permission enforcement, administrator bootstrap, export/retention, backup restore and actual member workflows are **not yet production-ready**. Do not enter real names, contacts or birth dates until R03/R06/R07/R15/R18 release gates are accepted.

## No-cost and production operating boundaries

Development requires free/open-source components and locally available compute. Running a server, domain, TLS certificate delivery, backups and monitoring **may incur charges** depending on the host; the project does not purchase or provision paid services on a user's behalf.

Before any real-data pilot, the operator must document (1) hosting and recurring cost, (2) secrets/TLS policy, (3) backup frequency and encrypted storage, (4) demonstrated restore, (5) upgrade/rollback procedures, (6) identity-provider recovery and deliberate invalidation of restored browser sessions and unfinished logins, (7) membership lifecycle and data retention, and (8) who is accountable for privacy requests. The current document is a checklist, not a tested production runbook. For a **synthetic, isolated** local backup/restore procedure and a synthetic-only GitHub CI restore plus recovered API startup drill (still not approved for production recovery), see [backup and restore rehearsal](backup-restore-rehearsal.md).

## Deployment acceptance: synthetic demo versus real-data pilot (2026-11-16)

**Operator approval remains open:** The restricted bootstrap/recovery commands exist and their synthetic tests are implemented. [#16](https://github.com/dkoller/OpenClub/issues/16) is closed for engineering; [#180](https://github.com/dkoller/OpenClub/issues/180) tracks witnessed recovery approval and sign-off for M2. The current Compose web port is localhost-only and nginx listens on HTTP; a real TLS ingress and externally reachable deployment have **not** been demonstrated. Current /health endpoints are liveness only, not database/OIDC readiness.

**M1 HTTPS/OIDC remote acceptance probe (read-only, PR #223):** After a real
HTTPS ingress and issuer have been configured by an authorized operator, run
the following from a workstation with `bash`, `curl` and `jq` (these tools
are not required by the default Compose startup):

```sh
OPENCLUB_DEMO_ORIGIN=https://club.example.org \
OPENCLUB_OIDC_ISSUER=https://idp.example.org/realms/openclub \
bash dev/verify-synthetic-https-demo.sh --synthetic-only
```

Replace the illustrative domains with actual operator-controlled HTTPS endpoints.
The script requires publicly trusted TLS (never `curl -k`), an exact HTTPS
application origin, live API/database readiness with no-store/MIME headers, 401 no-store anonymous club denial with CSP, plus 401 no-store denial for anonymous session, member list/detail, team and permission-grant reads (without collecting protected response bodies),
browser framing/referrer/MIME defenses plus the expected same-origin CSP and restricted browser permissions policy, and issuer-pinned OIDC discovery/JWKS
over HTTPS. It sends **no credentials or member data** and writes temporary
response files outside the checkout with restrictive permissions. It fails
closed when given HTTP endpoints. CI tests the script's shell syntax and
negative input behavior, **not** a hosted environment.

Record the actual deployment URL, timestamp, command exit code and an
operator-reviewed summary in the restricted M1 acceptance record, without
committing real account identifiers or credentials. A passing probe alone
is **not M1 sign-off**: actual Keycloak sign-in, verified first-admin bootstrap,
full synthetic member/team/grant DE/EN/FR/IT browser journey, restart
persistence, resource/cost limits, and the remaining privacy/security checks
must still be witnessed. An unavailable free HTTPS host is a deployment
blocker to document in #27/#28, not permission to drop TLS.

**M1 — deployed synthetic demo:** Document the actual target and URL, free-tier limits or no-cost infrastructure assumption, runtime resources, reproducible build/deploy, TLS, OIDC callback/proxy settings, secrets injection, health checks, data persistence after restart, and a full browser member/team/grant demo. Use synthetic identities and member data only. Do not claim deployment completion from local Compose smoke tests or PostgreSQL restore CI. Track acceptance evidence in [R19/#28](https://github.com/dkoller/OpenClub/issues/28).

**M2 — production pilot:** In addition to M1, require an accountable operator and approved privacy/legal rules before real member data, externally reachable production-domain/TLS and secure OIDC provisioning, separate per-club encrypted backup storage and retention, a witnessed restore and Flyway upgrade/rollback rehearsal, monitoring/alerts, secret rotation, operator recovery authorization, and an approved operating cost and incident plan. Track operational proof in [R18/#27](https://github.com/dkoller/OpenClub/issues/27). Production readiness is **not** implied by the synthetic CI drill.

The project targets no mandatory paid services, not a promise that permanent public hosting is free. Capture realistic resource measurements and any provider free-tier restrictions before choosing a host; do not provision paid resources without explicit approval. If no suitable cost-free public host is available, record the blocker rather than weakening security or claiming M1 deployed.

## Verified evidence versus outstanding M1/M2 actions (through PR #268)

| Evidence available | Still required before claiming completion |
| --- | --- |
| Disposable Keycloak/Chromium member, team, role and permission journeys in DE/EN/FR/IT, including synthetic 401/403 denial and retry tests (#256–#268) | Actual operator-approved external HTTPS/OIDC host, configured callbacks, deployment URL, witnessed end-to-end synthetic browser journey and restart/persistence proof for M1 (#28) |
| Optional Caddy TLS overlay, HSTS/header probe and PostgreSQL readiness/backup-restoration checks in CI | Actual external DNS/secret/TLS evidence, operator resource and zero-cost constraints plus hosted restart/backup evidence (#27) |
| Synthetic authorization, session revocation and private UI access-denial regressions | Reviewed privacy/lawful basis, private field and data lifecycle decisions, independent security/operator recovery/backup acceptance and explicit M2 release approval (#12/#13/#24/#26/#180) |

Do not check off hosted proof because local CI succeeds. Do not import or process real member data in the synthetic demo.

## Next engineering gates

- [#74](https://github.com/dkoller/OpenClub/issues/74): architecture and schema foundation completed in merged PR #79.
- [R07/#16](https://github.com/dkoller/OpenClub/issues/16): operator-only bootstrap and sole-admin recovery implemented (#16 engineering closed); witnessed operational recovery and production authorization remain open in #180 and #27.
- [R04/#13](https://github.com/dkoller/OpenClub/issues/13), [R06/#15](https://github.com/dkoller/OpenClub/issues/15): approved contracts and server authorization.
- [R15/#24](https://github.com/dkoller/OpenClub/issues/24), [R18/#27](https://github.com/dkoller/OpenClub/issues/27): local security tests and operational validation.
