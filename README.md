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
- [Implementation roadmap](docs/concepts/ai/first-milestone.md)
- [AI-assisted development concept](docs/concepts/ai/ai-agent-development.md)
- [Agent contribution rules](AGENTS.md)

## Contributing

Run locally with `docker compose up -d --build`; stop with `docker compose down` (data persists). See [cross-platform startup](docs/concepts/ai/one-command-run.md). For native development, use the checked-in `apps/api/gradlew` (or `gradlew.bat` on Windows) and `pnpm install --frozen-lockfile` in `apps/web`. Keep each PR under 2,000 changed lines and follow [agent rules](AGENTS.md).

## License

[MIT](LICENSE).
