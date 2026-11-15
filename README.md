# OpenClub

An open-source project exploring a free-to-use platform for clubs, starting with sports clubs and a floorball-club use case.

**Status:** Single-club pre-production reset merged in [PR #79](https://github.com/dkoller/OpenClub/pull/79); PR-size enforcement restored in [PR #80](https://github.com/dkoller/OpenClub/pull/80). Verified OIDC login, guarded single-club lookup, four-language login landing, and fresh local schema exist. The language selector honors a previously saved preference or the browser language (DE/EN/FR/IT), falls back to German, and stores the next selection locally; it does not send a language preference to the backend. Operator-only first-admin bootstrap and recovery are implemented. Member/team/grant database foundations are in place, but member/team/permission HTTP workflows and the full UI are **not implemented**. Privacy/legal approval and production readiness remain outstanding.

## Goal

Help volunteers manage club administration without requiring expensive software or a large technical team. Build for one real club first while keeping the core concepts extensible to other sports and non-profit clubs.

## First vertical slice

The pilot administrator approved a first usable release demonstrating **member management, team assignments and configurable club/team-scoped permissions** in **German, English, French and Italian**. Members may be entered before login; invitations come later. The first existing system targeted for replacement is Excel membership administration.

Each independent installation owns its club's member data: no shared database, cross-installation data transfer, or automatic synchronization. First/last names and date of birth are required for all; adult email and phone required, optional for minors; player gender required only if configured by that club. These are **requirements**, not implemented features or permission to use real personal data. See [accepted R01 pilot requirements](docs/concepts/product/r01-pilot-validation.md) and [R01/#10](https://github.com/dkoller/OpenClub/issues/10).

## Project documents

- [MVP and boundaries](docs/concepts/product/mvp.md)
- [Technical decision record](docs/decisions/0001-stack-selection.md)
- [Authentication architecture](docs/decisions/0003-oidc-session-proposal.md)
- [Optional local Keycloak setup](docs/development/keycloak-local.md)
- [Membership and privacy-related product decisions](docs/decisions/0004-member-policy.md)
- [Numbered roadmap, GitHub issues and completed milestones](docs/roadmap.md)
- [Single-club deployment and safe fresh-database setup](docs/deployment/single-club.md)
- [Original milestone concept](docs/concepts/ai/first-milestone.md)
- [AI-assisted development concept](docs/concepts/ai/ai-agent-development.md)
- [Agent contribution rules](AGENTS.md)
- [Documentation maintenance for every PR](docs/contributing/documentation.md)
- [Security implementation and review checklist](docs/contributing/security-review.md)

## Contributing

Run locally with `docker compose up -d --build`; stop with `docker compose down` (data persists). See [cross-platform startup](docs/concepts/ai/one-command-run.md). For native development, use the checked-in `apps/api/gradlew` (or `gradlew.bat` on Windows) and `pnpm install --frozen-lockfile` in `apps/web`. Keep each PR below 1,000 changed lines, follow [agent rules](AGENTS.md), and update [affected documentation](docs/contributing/documentation.md) in the **same PR**.

## License

[MIT](LICENSE).


## Deployment scope decision (2026-11-15)

OpenClub targets **one club per installation**, with an independently operated database and configuration; the same open-source code can serve different clubs via separate deployments. The fresh single-club schema and basic authenticated club lookup are implemented in [PR #79](https://github.com/dkoller/OpenClub/pull/79) under [accepted ADR-0005](docs/decisions/0005-single-club-installation.md). Old development database volumes are **not migrated**: reset only deliberately disposable pre-production data, as documented in the [deployment guide](docs/deployment/single-club.md). This is **not** production/privacy approval.
