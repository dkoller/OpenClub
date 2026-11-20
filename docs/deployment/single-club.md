# Operating one OpenClub club per installation

**Operator navigation (2026-11-17):** Use this runbook for the **current synthetic single-installation** configuration; [Keycloak local setup](../development/keycloak-local.md) and [Rauthy evaluation](../development/rauthy-local.md) are separate development fixtures, not production-ready identity services. The [M1 evidence checklist](m1-resource-evidence.md) is the authority for missing externally hosted acceptance, real resource/cost measurements and persistence; the [admin recovery procedure](../security/admin-recovery-procedure.md) requires independent human approval before real-data operations. Never treat synthetic CI restart/restore or reference TLS examples as proof of external hosting or M2 approval.

**Decision:** 2026-11-15. **Current operations status 2026-11-17 (through merged #306):** One-club synthetic pre-production application with local Compose, optional Caddy HTTPS/OIDC reference configuration, Keycloak and Rauthy as alternative issuers, and browser/API CI. Disposable Compose CI now additionally proves synthetic-club persistence across PostgreSQL/API/web restarts (#305), and records non-peak container resource samples (#301). **No operator-hosted external TLS/OIDC demonstration, validated 4 GB host footprint or live-member-data authorization exists.**

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
| `OPENCLUB_BOOTSTRAP_ACCOUNT_ID` | UUID of that previously verified local account, in canonical 8-4-4-4-12 form (shortened spellings are rejected) |
| `OPENCLUB_BOOTSTRAP_CLUB_NAME` | Club display name |
| `OPENCLUB_BOOTSTRAP_CLUB_SLUG` | Unique lowercase slug |
| `OPENCLUB_BOOTSTRAP_FIRST_NAME`, `OPENCLUB_BOOTSTRAP_LAST_NAME` | First administrator's local member names |
| `OPENCLUB_BOOTSTRAP_BIRTH_DATE` | ISO date `YYYY-MM-DD` |
| `OPENCLUB_BOOTSTRAP_EMAIL`, `OPENCLUB_BOOTSTRAP_PHONE` | First administrator's contact fields |

From `apps/api`, run `./gradlew bootstrapFirstClub` (Windows: `gradlew.bat bootstrapFirstClub`). The task uses the ordinary application dependencies and configured PostgreSQL database. It fails closed if the club or any local members already exist, the selected account lacks a verified identity binding, or required fields fail validation. A transaction-level advisory lock serializes concurrent bootstrap attempts; the database singleton constraint remains a second line of defense. The command creates no OIDC credentials, browser session, or web-exposed admin registration endpoint. The operator bootstrap atomically gives the verified first ADMIN explicit `MANAGE_PERMISSIONS`, `VIEW_MEMBER_DIRECTORY`, `EDIT_MEMBERS`, `VIEW_TEAMS`, `MANAGE_TEAMS`, (since OpenAPI 1.12.0) `MANAGE_FEES` and (since 1.14.0) `POST_ANNOUNCEMENTS` club grants; it does not grant those rights to other accounts or administrators. Upgrading an already initialized pre-production installation does **not** auto-grant permission-management rights: handle that exceptional provisioning through a separately reviewed operator procedure, never through unrestricted web setup.

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

All four recovery IDs must be canonical 8-4-4-4-12 UUIDs. The command rejects shortened spellings such as `1-1-1-1-1` before connecting to the database, so a typo cannot silently expand into a different ID.

Run `./gradlew recoverSoleAdmin` from `apps/api` (Windows: `gradlew.bat recoverSoleAdmin`). Do not put private IDs, credentials or approval evidence in committed files, command-line arguments, CI job output or shared logs. The command locks and verifies the one club and sole-admin member, requires a verified replacement identity held stable through recovery, rejects linked replacements, changes the member account association atomically, and **revokes existing sessions for both the old and replacement accounts in the same transaction**. This prevents an already signed-in replacement account from inheriting administrator privileges without a new login. A retry with stale IDs fails; concurrent recoveries and protected role/grant mutations are serialized so the sole-admin condition cannot change during recovery. A failed command leaves all access unchanged.

Afterward, require the replacement administrator to sign in again and verify that the new session can access the club and that the old account cannot access club resources, record the outcome in the private incident log, and rotate/revoke the old provider identity if compromised. Do not delete the previous OIDC account or the existing club/member record as a shortcut. If there is no existing administrator member, more than one administrator, no trusted replacement identity, or a database inconsistency, this narrow recovery procedure intentionally refuses to act. Any broader manual repair requires a separate reviewed decision and backup. **This is not privacy/legal approval or readiness for real personal data.**

## Reading the audit trail (operator-only, read-only)

From `apps/api`, run `./gradlew showAuditTrail` (Windows: `gradlew.bat showAuditTrail`) with the same `DB_URL`, `DB_USER` and `DB_PASSWORD` as the API. It prints the audit trail newest first as tab-separated lines: id, UTC time, event, acting account, target member, capability, scope, team, new role and related member (the guardian, for guardianship events). Empty cells show `-`. The trail holds identifiers only, never names or contacts, and the command writes nothing. Optional filters, all validated before any connection: `OPENCLUB_AUDIT_SINCE` (an ISO instant such as `2026-11-18T10:00:00Z`, or a date, read as UTC), `OPENCLUB_AUDIT_MEMBER` (a canonical member UUID) and `OPENCLUB_AUDIT_LIMIT` (1 to 5000, default 200). Attach the output for the incident window to a [recovery case](../security/admin-recovery-case-template.md) rather than copying identifiers into tickets. The 12-month retention rule deletes older events ([retention cleanup](../security/retention-cleanup.md)).

## Exporting one member's data (operator-only)

For a verified access or portability request, an operator can print everything the installation stores about one member as JSON. From `apps/api`, run `./gradlew -q exportMember` (Windows: `gradlew.bat -q exportMember`) with `DB_URL`, `DB_USER`, `DB_PASSWORD`, `OPENCLUB_EXPORT_CONFIRM=export-member` and `OPENCLUB_EXPORT_MEMBER_ID` (the member's canonical UUID), both validated before any connection. **The output contains personal data:** redirect it to a private file created with a restrictive umask (`umask 077`) and outside the repository, deliver it to the verified requester out of band, then dispose of it under the controller's policy. Messages go to standard error, so the file holds only the JSON.

The export holds the member's own stored fields (names, date of birth, email, phone, gender, player flag, role, whether the name is on the public list, created and archive timestamps), their team assignments, their invitation timestamps (never the secret or its hash), their membership fees (since OpenAPI 1.12.0), their attendance answers (since 1.14.1), their volunteer shift sign-ups (since 1.16.0), their absences (since 1.20.0), their ride offers and seats (since 1.21.0), their club grants and their team grants. A linked login is reported only as `hasLinkedLogin` yes or no. It leaves out the login binding (issuer and subject), the calendar feed link and its hash (the member can see in *My schedule* whether one exists), sessions, other people's data and the audit trail, because the trail's actor identifiers belong to other people. Active, archived and linked members can all be exported; an unknown ID exports nothing. Every export that produced data records an identifier-only `MEMBER_EXPORTED` audit event (no actor, operator command). Verify the requester's identity and record the request in the private register first, as for erasure. To export before an erasure, run this command, then follow [Erasing a member](#erasing-a-member-operator-only-permanent).

## Erasing a member (operator-only, permanent)

Erasure permanently deletes one **archived** member (owner-approved default: hard delete after archive). It is irreversible and has no undo. If the requester also asked for a copy, [export the member first](#exporting-one-members-data-operator-only).

1. **Verify first, outside the repository.** Record the verified request, the requester's identity check and the controller's decision in the private register (not in committed files, tickets or command arguments). Respond within the controller's deadline (proposed default: 30 days) unless a statutory duty to keep the data applies.
2. **Archive the member** in the administration screen. Archiving clears team assignments and grants. Linked members and administrators cannot be archived, so they cannot be erased: there is no member unlink operation yet (it comes with invitations).
3. **Run** `./gradlew eraseMember` from `apps/api` (Windows: `gradlew.bat eraseMember`) with `DB_URL`, `DB_USER`, `DB_PASSWORD` and three variables: `OPENCLUB_ERASE_CONFIRM=erase-archived-member`, `OPENCLUB_ERASE_MEMBER_ID` (the member's canonical UUID) and `OPENCLUB_ERASE_NAME` (the member's full name exactly as stored, first name then surname, which guards against the wrong record). All three are validated before any connection. Every refusal prints the same generic message.
4. **Check** that the command reported success; an identifier-only `MEMBER_ERASED` event is in the audit trail (`showAuditTrail`), kept for the 12-month audit retention. Record the outcome in the private register.
5. **Backups.** Erasure does not edit backups; the member remains in older backups until they expire (proposed default: 30 days rolling). **After any restore, re-run the erasures recorded since that backup was taken** before the system is used again.

Archived members are also erased automatically **24 months after archiving** by the hourly retention job ([retention cleanup](../security/retention-cleanup.md)). There is **no legal-hold switch yet**: the controller must decide on one before any real member data.

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

**One-host demo on Infomaniak (owner decision 2026-11-19):** the [Infomaniak runbook](infomaniak-demo.md) adds a production-configured Rauthy on the same host (`compose.rauthy.demo.yaml`, `dev/deployment/Caddyfile.rauthy`) and generates all secrets with `dev/deployment/generate-demo-env.sh`. It is the recommended path for the hosted M1 demo; the overlay below remains the reference for an external identity provider.

`compose.https.demo.yaml` is an **optional** reference overlay for a host that an
operator already controls. It is not used by the local development command,
does not provision hosting/DNS, and does not constitute a deployed M1 demo.
The overlay uses the pinned open-source Caddy `2.11.9-alpine` image as an
ACME TLS reverse proxy to the existing nginx/web and Kotlin API; only
ports **80 and 443** are published. The default HTTP web and PostgreSQL host
ports are reset to empty in this overlay. All four services use
`restart: unless-stopped`, allowing Docker to restart them after a host reboot
when the Docker daemon itself starts. This is **not** a guarantee of startup
order or provider health: verify `/api/ready`, real synthetic OIDC login and
persistent volumes after an actual host reboot. Caddy stores certificate and
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
is **not M1 sign-off**: actual sign-in via the selected Keycloak or Rauthy issuer, verified first-admin bootstrap,
full synthetic member/team/grant DE/EN/FR/IT browser journey, restart
persistence, resource/cost limits, and the remaining privacy/security checks
must still be witnessed. An unavailable free HTTPS host is a deployment
blocker to document in #27/#28, not permission to drop TLS.

**M1 — deployed synthetic demo:** Document the actual target and URL, free-tier limits or no-cost infrastructure assumption, runtime resources, reproducible build/deploy, TLS, OIDC callback/proxy settings, secrets injection, health checks, data persistence after restart, and a full browser member/team/grant demo. Use synthetic identities and member data only. Do not claim deployment completion from local Compose smoke tests or PostgreSQL restore CI. Track acceptance evidence in [R19/#28](https://github.com/dkoller/OpenClub/issues/28).

**M2 — production pilot:** In addition to M1, require an accountable operator and approved privacy/legal rules before real member data, externally reachable production-domain/TLS and secure OIDC provisioning, separate per-club encrypted backup storage and retention, a witnessed restore and Flyway upgrade/rollback rehearsal, monitoring/alerts, secret rotation, operator recovery authorization, and an approved operating cost and incident plan. Track operational proof in [R18/#27](https://github.com/dkoller/OpenClub/issues/27). Production readiness is **not** implied by the synthetic CI drill.

The project targets no mandatory paid services, not a promise that permanent public hosting is free. Capture realistic resource measurements and any provider free-tier restrictions before choosing a host; do not provision paid resources without explicit approval. If no suitable cost-free public host is available, record the blocker rather than weakening security or claiming M1 deployed.

## Historical evidence snapshot versus outstanding M1/M2 actions (through PR #268; see latest #301–#306 evidence above)

| Evidence available | Still required before claiming completion |
| --- | --- |
| Disposable Keycloak/Chromium member, team, role and permission journeys in DE/EN/FR/IT, including synthetic 401/403 denial and retry tests (#256–#268) | Actual operator-approved external HTTPS/OIDC host, configured callbacks, deployment URL, witnessed end-to-end synthetic browser journey and restart/persistence proof for M1 (#28) |
| Optional Caddy TLS overlay, HSTS/header probe and PostgreSQL readiness/backup-restoration checks in CI | Actual external DNS/secret/TLS evidence, operator resource and zero-cost constraints plus hosted restart/backup evidence (#27) |
| Synthetic authorization, session revocation and private UI access-denial regressions | Reviewed privacy/lawful basis, private field and data lifecycle decisions, independent security/operator recovery/backup acceptance and explicit M2 release approval (#12/#13/#24/#26/#180) |

Do not check off hosted proof because local CI succeeds. Do not import or process real member data in the synthetic demo.

## Upgrading and rolling back

See the [upgrade and rollback runbook](upgrade-and-rollback.md): backup, preflight queries for data that
blocks a migration, the upgrade itself, failure handling, and rollback by restoring the pre-upgrade backup.
It is covered by synthetic migration tests, not by a witnessed production drill.

## Next engineering gates

- [#74](https://github.com/dkoller/OpenClub/issues/74): architecture and schema foundation completed in merged PR #79.
- [R07/#16](https://github.com/dkoller/OpenClub/issues/16): operator-only bootstrap and sole-admin recovery implemented (#16 engineering closed); witnessed operational recovery and production authorization remain open in #180 and #27.
- [R04/#13](https://github.com/dkoller/OpenClub/issues/13): approved versioned contract. Server authorization ([R06/#15](https://github.com/dkoller/OpenClub/issues/15)) is implemented and closed; its remaining edge cases are tracked in [#24](https://github.com/dkoller/OpenClub/issues/24).
- [R15/#24](https://github.com/dkoller/OpenClub/issues/24), [R18/#27](https://github.com/dkoller/OpenClub/issues/27): local security tests and operational validation.

## Calendar feed links in logs

Since OpenAPI 1.17.0 a member's calendar feed link carries its secret in the path (`/api/v1/calendar/<secret>`). The API does not log request paths and the bundled `apps/web/nginx.conf` disables the access log for `/api/v1/calendar/`. If you put another proxy or load balancer in front, turn its access log off for that path too (Caddy as configured in `dev/deployment/Caddyfile` keeps no access log). A leaked link is revoked by the member in *My schedule*.

## Email notifications (optional)

Since OpenAPI 1.19.0 the API can send notification emails through any SMTP server the club already uses (for example a mailbox in the club's Infomaniak plan). Without `OPENCLUB_SMTP_HOST` email is off and nothing is queued; the synthetic demo stays that way, because its members have made-up addresses. To turn it on for a real-data installation (after the M2 gates), add the settings to the `api` service through a compose override file kept with the server's other configuration, never on a command line:

```yaml
# compose.email.yaml (on the server, next to the other override files; the password comes from the private env file)
services:
  api:
    environment:
      OPENCLUB_SMTP_HOST: mail.example.ch        # the provider's SMTP host name
      OPENCLUB_SMTP_PORT: "465"                  # 465 = implicit TLS (default); 587 = STARTTLS
      OPENCLUB_SMTP_USERNAME: club@example.ch
      OPENCLUB_SMTP_PASSWORD: ${OPENCLUB_SMTP_PASSWORD:?set in the private env file}
      OPENCLUB_SMTP_FROM: "FC Example <club@example.ch>"
      OPENCLUB_TIME_ZONE: Europe/Zurich          # times in emails and absence days (default)
```

The API refuses to start with a partial or invalid email setup. It needs the OIDC settings (the browser origin is the link in every message). Plain-text SMTP is never used. The SMTP provider processes members' addresses and message contents for the club: list it in the processing record (C8) and conclude a data processing agreement before real data. Messages wait at most 2 days in the database; failures are logged as a count only.

