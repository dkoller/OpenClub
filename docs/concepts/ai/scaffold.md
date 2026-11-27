# First runnable scaffold

## Requirements

- Java 21, Node.js 22, pnpm 10.24.0, Docker. Gradle is provided by the checked-in wrapper. For container-only use, Docker Compose is sufficient.
- The selected architecture is documented in [ADR-0001](../../decisions/0001-stack-selection.md).

## Start locally

```sh
docker compose up -d postgres
cd apps/api
DB_URL=jdbc:postgresql://localhost:5432/openclub \
DB_USER=openclub DB_PASSWORD=openclub_dev ./gradlew run
```

In a second terminal:

```sh
cd apps/web
pnpm install --frozen-lockfile
pnpm dev
```

Visit http://localhost:5173. The page should report **API connected**. The API exposes only `GET /health` (a liveness endpoint, not authorization or readiness).

These credentials are **development-only**, and the database is bound to localhost in Compose. Set secure environment variables for any nonlocal instance.

## Test

```sh
cd apps/api && ./gradlew test
cd apps/web && pnpm test && pnpm build
```

The Kotlin integration test uses a real PostgreSQL database when DB_URL, DB_USER and DB_PASSWORD are supplied. Without these, it is skipped; CI supplies PostgreSQL using a GitHub Actions service. Docker is needed to start the local Compose database.

## Flyway and jOOQ

Setting `DB_URL`, `DB_USER` and `DB_PASSWORD` on API startup migrates the database via Flyway and performs a small jOOQ query. Without DB_URL, the health-only scaffold can start without a database.

The API uses jOOQ's plain-SQL API with bind parameters and generates no code (ADR-0001, amendment of 2026-11-17).

On Windows without WSL, use `gradlew.bat` instead of `./gradlew`. Do not manually edit `pnpm-lock.yaml`: update it using `pnpm install` when dependencies change.

## Deferred intentionally

- Authentication, membership APIs, deployment and business logic
- Continuous database connection pool and generated jOOQ types wired into feature code. *(2026-11-17: the pool exists; feature code intentionally uses plain-SQL jOOQ rather than generated types; see the ADR-0001 amendment.)*

The first code PR establishes a working skeleton, not a production-ready service.
