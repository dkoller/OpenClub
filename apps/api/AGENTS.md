# Kotlin backend agent instructions

Read root `AGENTS.md` and ADR-0001 first.

- Kotlin + Ktor; PostgreSQL; **jOOQ is mandatory** for database access; use Flyway migrations.
- Do not introduce Hibernate/JPA or Exposed as an alternative persistence layer.
- Keep jOOQ code and generated database records out of public API DTOs.
- Prefer concrete services and data access classes; add abstractions only where they provide an actual boundary.
- SQL/code generation must be reproducible from versioned schema migrations.
- When changing routes, migrations, authentication, environment variables or build/test commands, update the OpenAPI contract, relevant ADRs and developer/runbook docs **in the same PR**. Check [documentation maintenance](../../docs/contributing/documentation.md).
- Validate requests, enforce authorization and club isolation server-side, and use explicit transactions where appropriate.
- For authentication, URL parsing and provider/container changes, follow the [security review checklist](../../docs/contributing/security-review.md); verify relevant failure cases and actual runtime behavior.
- jOOQ JDBC work is blocking: do not run it on an event-loop thread. Document the dispatcher/connection-pool approach before implementation.
- Test SQL and migrations against PostgreSQL, including singleton-club enforcement, accountless records, required member fields, and local authorization denial.
- The internal `MemberAccess` lookup accepts an installation-local account ID from **verified authentication**, never untrusted request input. It is not by itself an HTTP authorization guard. See `docs/security/club-membership-access.md`.
- Do not add service/facade/repository layers solely for ceremony.
- Keep Kotlin formatting, static analysis and tests part of the normal PR checks once the backend is scaffolded.


**Package boundaries:** `auth` (OIDC/sessions), `database` (pool and Flyway), `members` (local member access), `club` (installation club endpoint), and root application wiring. Keep files focused; do not recreate one giant Kotlin package or add abstraction layers without need.
