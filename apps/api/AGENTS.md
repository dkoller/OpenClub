# Kotlin backend agent instructions

Read root `AGENTS.md` and ADR-0001 first.

- Kotlin + Ktor; PostgreSQL; **jOOQ is mandatory** for database access; use Flyway migrations.
- Do not introduce Hibernate/JPA or Exposed as an alternative persistence layer.
- Keep jOOQ code and generated database records out of public API DTOs.
- Prefer concrete services and data access classes; add abstractions only where they provide an actual boundary.
- SQL/code generation must be reproducible from versioned schema migrations.
- Validate requests, enforce authorization and club isolation server-side, and use explicit transactions where appropriate.
- jOOQ JDBC work is blocking: do not run it on an event-loop thread. Document the dispatcher/connection-pool approach before implementation.
- Test SQL and migrations against PostgreSQL, including cross-club access denial.
- Do not add service/facade/repository layers solely for ceremony.
- Keep Kotlin formatting, static analysis and tests part of the normal PR checks once the backend is scaffolded.
