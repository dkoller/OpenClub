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

**First-club setup:** the database now enforces at most one club row; secure first-admin provisioning and recovery are still [R07/#16](https://github.com/dkoller/OpenClub/issues/16), **not an unauthenticated public setup endpoint**. No second club may be created in an installation. Use synthetic test fixtures until bootstrap and privacy are approved.

## Fresh database only — schema reset

This branch replaces the previous Flyway V1–V4 chain with a fresh `V1__single_club.sql`. It intentionally does **not** migrate legacy database volumes. Existing Flyway history must fail rather than silently merge/delete old tenant data.

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

- [#74](https://github.com/dkoller/OpenClub/issues/74): complete local member/account schema and initialization, with tests.
- [R07/#16](https://github.com/dkoller/OpenClub/issues/16): protected one-time club and first-admin bootstrap.
- [R04/#13](https://github.com/dkoller/OpenClub/issues/13), [R06/#15](https://github.com/dkoller/OpenClub/issues/15): approved contracts and server authorization.
- [R15/#24](https://github.com/dkoller/OpenClub/issues/24), [R18/#27](https://github.com/dkoller/OpenClub/issues/27): local security tests and operational validation.
