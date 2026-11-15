# Operating one OpenClub club per installation

**Decision:** 2026-11-15. **Status:** Pre-production implementation in progress; no live member data permitted yet.

Each club runs an independent application process (API + web), PostgreSQL database, application configuration and backups. The same MIT-licensed source can serve any club; never share a PostgreSQL database or schema, session cookie secret, OIDC client configuration or member records across installations.

## Start for development

From the repository root, using a local Docker installation and a unique local database password in a **gitignored** environment file:

```sh
docker compose up -d --build
docker compose ps
curl --fail http://localhost:5173/api/health
```

The basic stack starts without authentication. OIDC is opt-in; without it there are **no member administration endpoints enabled**. To configure a local test OIDC provider, follow [Keycloak development setup](../development/keycloak-local.md). The nginx/Vite proxies preserve `/api` so Ktor routes remain reachable.

**First-club setup:** an operator-only bootstrap command now creates the sole club and first ADMIN member atomically. It is **not an HTTP route**. No second club can be created. The human-reviewed recovery process remains open in [R07/#16](https://github.com/dkoller/OpenClub/issues/16). Use **synthetic data only** until privacy and production requirements are approved.

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

From `apps/api`, run `./gradlew bootstrapFirstClub` (Windows: `gradlew.bat bootstrapFirstClub`). The task uses the ordinary application dependencies and configured PostgreSQL database. It fails closed if the club or any local members already exist, the selected account lacks a verified identity binding, or required fields fail validation. A transaction-level advisory lock serializes concurrent bootstrap attempts; the database singleton constraint remains a second line of defense. The command creates no OIDC credentials, browser session, or web-exposed admin registration endpoint.

**Recovery:** Never rerun bootstrap on an initialized installation and never reset a live database to recover access. Lost-admin recovery requires a separately approved operator procedure with identity re-verification, backup, a recorded authorization decision, and a reviewed transactional change; it is not implemented in this PR.

## Fresh database only — schema reset

Merged PR #79 replaces the previous Flyway V1–V4 chain with a fresh `V1__single_club.sql`. It intentionally does **not** migrate legacy database volumes. Existing Flyway history must fail rather than silently merge/delete old tenant data.

**Destructive local-only reset:** stop a disposable development Compose stack and explicitly remove its disposable PostgreSQL volume *only after confirming there is no data to keep and that a backup is unnecessary*. Never run volume deletion against a shared or production installation. You can create a new named Compose project instead:

```sh
COMPOSE_PROJECT_NAME=openclub_single_club_test docker compose up -d --build
```

Keep separate project names/volumes per club. Do not point two installations at the same `DB_URL`, database or schema. The singleton database constraint does not by itself enforce physical isolation if operators intentionally reuse credentials.

## Authentication, privacy and access

- Sessions use the installation's verified OIDC issuer and subject, an opaque HttpOnly cookie and server-side PostgreSQL session state.
- Signing in does not automatically give any club/member access. `GET /api/v1/club` only responds to an authenticated account linked to a local member. Missing membership returns 403; missing session returns 401.
- Local member first/last name and DOB are required, both email and phone for adults, and gender only where required for players by local club policy. Adult threshold, safe member API validation and minors' visibility require explicit R03/R04 completion.
- Club/team permission enforcement, administrator bootstrap, export/retention, backup restore and actual member workflows are **not yet production-ready**. Do not enter real names, contacts or birth dates until R03/R06/R07/R15/R18 release gates are accepted.

## No-cost and production operating boundaries

Development requires free/open-source components and locally available compute. Running a server, domain, TLS certificate delivery, backups and monitoring **may incur charges** depending on the host; the project does not purchase or provision paid services on a user's behalf.

Before any real-data pilot, the operator must document (1) hosting and recurring cost, (2) secrets/TLS policy, (3) backup frequency and encrypted storage, (4) demonstrated restore, (5) upgrade/rollback procedures, (6) identity-provider recovery, (7) membership lifecycle and data retention, and (8) who is accountable for privacy requests. The current document is a checklist, not a tested production runbook.

## Next engineering gates

- [#74](https://github.com/dkoller/OpenClub/issues/74): architecture and schema foundation completed in merged PR #79.
- [R07/#16](https://github.com/dkoller/OpenClub/issues/16): complete operator recovery, threat review and bootstrap acceptance tests; initial operator CLI is implemented.
- [R04/#13](https://github.com/dkoller/OpenClub/issues/13), [R06/#15](https://github.com/dkoller/OpenClub/issues/15): approved contracts and server authorization.
- [R15/#24](https://github.com/dkoller/OpenClub/issues/24), [R18/#27](https://github.com/dkoller/OpenClub/issues/27): local security tests and operational validation.
