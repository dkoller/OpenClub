# First runnable scaffold

## Requirements

- Java 21, Gradle 9.7.0, Node.js 22, pnpm 10.24.0, Docker
- The selected architecture is documented in [ADR-0001](../../decisions/0001-stack-selection.md).

## Start locally

```sh
docker compose up -d postgres
cd apps/api
DB_URL=jdbc:postgresql://localhost:5432/openclub \
DB_USER=openclub DB_PASSWORD=openclub_dev gradle run
```

In a second terminal:

```sh
cd apps/web
pnpm install
pnpm dev
```

Visit http://localhost:5173. The page should report **API connected**. The API exposes only `GET /health` (a liveness endpoint, not authorization or readiness).

These credentials are **development-only**, and the database is bound to localhost in Compose. Set secure environment variables for any nonlocal instance.

## Test

```sh
cd apps/api && gradle test
cd apps/web && pnpm test && pnpm build
```

The Kotlin database integration test uses Testcontainers; Docker must be running.

## Flyway and jOOQ

Setting `DB_URL`, `DB_USER` and `DB_PASSWORD` on API startup migrates the database via Flyway and performs a small jOOQ query. Without DB_URL, the health-only scaffold can start without a database.

After starting PostgreSQL and running the API once to migrate the schema, from `apps/api` run:

```sh
gradle generateJooq
```

This generates database types from the local migrated schema into `build/generated-src/jooq`. The generator XML uses **local-only** connection settings; change it to secure environment-provided credentials when configuring nonlocal code generation.

## Deferred intentionally

- Authentication, membership APIs, deployment and business logic
- CI-generated/pinned pnpm lockfile and a committed Gradle wrapper (requires a checked-in verified wrapper binary)
- Continuous database connection pool and generated jOOQ types wired into feature code

The first code PR establishes a working skeleton, not a production-ready service.
