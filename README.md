# OpenClub

An open-source project exploring a free-to-use platform for clubs, starting with sports clubs and a floorball-club use case.

**Status:** Early working scaffold: React/TypeScript frontend, Kotlin/Ktor API, PostgreSQL, jOOQ and Flyway. Membership workflows are not yet implemented.

## Goal

Help volunteers manage club administration without requiring expensive software or a large technical team. Build for one real club first while keeping the core concepts extensible to other sports and non-profit clubs.

## First vertical slice

A club administrator should be able to create a club, invite a member, assign a club role, and view a membership list with proper organization isolation.

This is a **working hypothesis**, not a final commitment. See [MVP scope](docs/concepts/product/mvp.md).

## Project documents

- [MVP and boundaries](docs/concepts/product/mvp.md)
- [Technical decision record](docs/decisions/0001-stack-selection.md)
- [Authentication architecture](docs/decisions/0003-oidc-session-proposal.md)
- [Optional local Keycloak setup](docs/development/keycloak-local.md)
- [Membership and privacy-related product decisions](docs/decisions/0004-member-policy.md)
- [Numbered roadmap, GitHub issues and completed milestones](docs/roadmap.md)
- [Original milestone concept](docs/concepts/ai/first-milestone.md)
- [AI-assisted development concept](docs/concepts/ai/ai-agent-development.md)
- [Agent contribution rules](AGENTS.md)
- [Documentation maintenance for every PR](docs/contributing/documentation.md)
- [Security implementation and review checklist](docs/contributing/security-review.md)

## Contributing

Run locally with `docker compose up -d --build`; stop with `docker compose down` (data persists). See [cross-platform startup](docs/concepts/ai/one-command-run.md). For native development, use the checked-in `apps/api/gradlew` (or `gradlew.bat` on Windows) and `pnpm install --frozen-lockfile` in `apps/web`. Keep each PR below 1,000 changed lines, follow [agent rules](AGENTS.md), and update [affected documentation](docs/contributing/documentation.md) in the **same PR**.

## License

[MIT](LICENSE).
