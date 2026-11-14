# Kotlin backend agent instructions

Read root `AGENTS.md` and ADR-0001 first.

- Kotlin + Ktor; PostgreSQL; **jOOQ is mandatory** for database access; use Flyway migrations.
- Do not introduce Hibernate/JPA or Exposed as an alternative persistence layer.
- Keep jOOQ code and generated database records out of public API DTOs.
- Prefer concrete services and data access classes; add abstractions only where they provide an actual boundary.
- SQL/code generation must be reproducible from versioned schema migrations.
- When changing routes, migrations, authentication, environment variables or build/test commands, update the OpenAPI contract, relevant ADRs and developer/runbook docs **in the same PR**. Check [documentation maintenance](../../docs/contributing/documentation.md).
- Validate requests, enforce authorization and club isolation server-side, and use explicit transactions where appropriate.
- For URLs that affect authentication or remote calls, validate hierarchical URI structure, parsed nonblank host, scheme, port, user-info and relevant path/query/fragment constraints. Java `URI.host` may be `null` for missing or unparsed Unicode authorities; reject or explicitly normalize IDN authorities before comparison. Test hostless, malformed, Unicode and wrong-host cases.
- For OIDC, test issuer/audience/signature and state/nonce/PKCE failures, replay/expiry and fail-closed behavior before exposing login. Do not trust request-controlled forwarding headers to build callback or issuer URLs.
- For Docker/provider changes, run a service-start and behavioral smoke test (for example OIDC discovery), not only Compose/JSON syntax validation. See [security review checklist](../../docs/contributing/security-review.md).
- jOOQ JDBC work is blocking: do not run it on an event-loop thread. Document the dispatcher/connection-pool approach before implementation.
- Test SQL and migrations against PostgreSQL, including cross-club access denial.
- The internal `ClubMembershipAccess` lookup takes a person ID resolved by **verified authentication**, never untrusted request input. It is not an HTTP authorization guard. See `docs/security/club-membership-access.md`.
- Do not add service/facade/repository layers solely for ceremony.
- Keep Kotlin formatting, static analysis and tests part of the normal PR checks once the backend is scaffolded.
