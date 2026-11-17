# ADR-0001: Application stack selection

- **Status:** Accepted
- **Date:** 2026-11-14
- **Scope:** Initial OpenClub web application

## Context

OpenClub aims to be a free, open-source, maintainable club-management platform. It will start with a practical sports-club use case while keeping the domain adaptable to multiple clubs and potentially other nonprofit organizations. The project should be approachable for contributors, friendly to AI-assisted development, and economical to deploy.

The team considered (1) a TypeScript frontend and backend, (2) a TypeScript frontend with a Kotlin backend, and (3) Kotlin across frontend and backend. The backend technology and database access approach have now been explicitly selected.

## Decision

Use a **TypeScript web frontend** with a **Kotlin/JVM backend**:

| Concern | Selected technology |
| --- | --- |
| Web frontend | React, TypeScript, Vite |
| Backend language/runtime | Kotlin on the JVM |
| Backend HTTP framework | Ktor |
| Database | PostgreSQL |
| SQL access | **jOOQ** (required) |
| Database migrations | Flyway |
| API interface | REST with an OpenAPI contract |
| Architecture | Modular monolith |
| Build tools | Gradle for backend; pnpm for frontend |
| Backend tests | JUnit 5; PostgreSQL-backed integration tests (GitHub Actions PostgreSQL service currently; Testcontainers optional later) |
| Frontend tests | Vitest; Playwright for browser flows |
| CI | GitHub Actions |

Authentication provider, deployment platform, UI component library, authorization mechanism, and specific framework/library versions are **not** decided by this ADR. See the implementation notes below for subsequent progress.

### Database access rules

- Use **jOOQ** as the primary database access layer, rather than Exposed, Hibernate/JPA, or ad hoc ORM repositories.
- Manage PostgreSQL schema changes using versioned Flyway migrations.
- Generate jOOQ database metadata/types from a migration-managed schema as part of a reproducible build or code-generation workflow; document the required build steps.
- Keep SQL/jOOQ code behind domain-facing repository/service boundaries. Do not leak generated jOOQ records into external API contracts.
- Use transactions deliberately for business operations spanning multiple statements.
- Test database behavior against PostgreSQL, including club/organization isolation; an in-memory substitute alone is not sufficient.

## Rationale

- **Kotlin and Ktor:** strong type safety and backend domain modeling with direct JVM ecosystem access.
- **jOOQ and PostgreSQL:** explicit, type-safe SQL gives control and visibility over complex queries and evolving relational data.
- **React and TypeScript:** established web tooling and accessible UI ecosystem suitable for forms and administration workflows.
- **OpenAPI contract:** a language-neutral boundary enables independent frontend/backend development and agent handoffs.
- **Modular monolith:** reduces operational and coordination overhead compared with early microservices.

## Alternatives considered

### TypeScript frontend and TypeScript backend

A compelling option for rapid prototyping and edge/serverless deployment, but not selected because Kotlin on the backend and jOOQ are explicit project preferences.

### Kotlin frontend and Kotlin backend

Could facilitate sharing Kotlin code across platforms, particularly with future mobile clients, but a React/TypeScript web UI is preferred for the initial browser-based application.

### Other Kotlin database access tools

Exposed, Hibernate/JPA and other ORM approaches were considered in general terms. **jOOQ is a firm choice** because direct, type-safe SQL and PostgreSQL-specific capabilities are valued.

## Consequences and trade-offs

- Two language and build ecosystems (TypeScript/pnpm and Kotlin/Gradle) add some CI and onboarding work.
- Running a JVM backend may constrain free-tier hosting choices compared with edge-oriented TypeScript runtimes; hosting cost and operational reliability must be evaluated separately.
- A PostgreSQL instance and repeatable migration/code-generation workflow are required for meaningful backend integration testing.
- Contract changes must be synchronized across Kotlin and TypeScript through the OpenAPI specification.
- Backend business logic and authorization stay server-side. Each request must enforce club/organization boundaries; frontend filtering is not a security control.
- jOOQ code generation, licensing suitability, and version compatibility should be verified when pinning dependencies. Prefer a configuration compatible with the project's open-source goals.

## Follow-up work

1. Select pinned, mutually compatible versions of Kotlin, Ktor, jOOQ, Flyway, PostgreSQL, Gradle and frontend dependencies.
2. Scaffold `apps/api` and `apps/web` with a small health endpoint and a minimal web page.
3. Configure PostgreSQL locally, Flyway migrations and reproducible jOOQ generation.
4. Add CI checks for builds, unit tests and PostgreSQL-backed integration tests.
5. Implement the initial club/membership workflow against the documented OpenAPI contract.

**This ADR records the stack decision only.** Application scaffolding and CI implementation belong in a separate pull request.

## Implementation status (2026-11-14; historical decision retained)

The implementation now uses a **GitHub Actions PostgreSQL service** for integration tests (see `.github/workflows/build.yml` and `apps/api/src/test`). **Testcontainers has not been adopted**; it is an optional future alternative, not a prerequisite for the current build. The repo also includes a Gradle wrapper, pnpm lockfile, a Compose smoke workflow and a draft (not yet approved) OpenAPI contract. See [roadmap](../roadmap.md) for current status. Keep this implementation note aligned with CI if database test infrastructure changes.

## Amendment: plain-SQL jOOQ access (2026-11-17, accepted by the repository owner)

**Context.** Every backend query on `main` uses jOOQ's plain-SQL API (`DSLContext.execute`/`fetch`/`fetchOne` with `?` bind parameters). The `generateJooq` task runs in CI against the migrated PostgreSQL service, but no feature code references the generated classes. That is deliberate in practice: the API Docker image and `docker compose up --build` compile the backend **without a database**, so feature code that depended on generated sources would break the one-command build.

**Decision.** For M1, OpenClub keeps jOOQ (still the only persistence layer; no ORM) and uses its **plain-SQL API with bind parameters** as the accepted database access style. The "type-safe SQL" rationale above is therefore only partly realised, and this is now recorded rather than implied.

- Never concatenate request input into SQL. Interpolate only compile-time fragments, such as the shared actor-authorization predicate in `apps/api/src/main/kotlin/org/openclub/api/members/ActorSql.kt`, which validates its aliases and embeds only enum names.
- Every query must be exercised by a PostgreSQL-backed test. That is the compensating control for the missing compile-time schema checking.
- CI keeps running `./gradlew test generateJooq`, which still checks that the migrated schema can be introspected. Generated sources stay in `build/` and are not checked in.

**Revisit when** the schema stabilises after R04/M2, or before a large data-access change. Moving to typed queries requires generation that needs no live database at image build time (for example checked-in generated sources or a migration-driven generator), plus a migration plan. Record that as a new ADR or amendment.
