# OpenClub

An open-source project exploring a free-to-use platform for clubs, starting with sports clubs and a floorball-club use case.

## Current status (2026-11-17)

OpenClub is a **pre-production, synthetic-data-only** application. Each installation serves **one club** with its own PostgreSQL database ([ADR-0005](docs/decisions/0005-single-club-installation.md)).

**Works today**, with test (fictional) data only:

- An authorized administrator can search, create, edit and archive members, manage teams and team assignments, change member roles (with last-administrator protection), grant and revoke club- or team-scoped permissions, and edit the club name and player-gender policy.
- The UI is available in German, English, French and Italian. Public member lists show adults' full names and minors' first name plus surname initial, and no private fields.
- Login uses OIDC through a backend-for-frontend with server-side sessions. Keycloak and Rauthy are both supported, with one issuer per installation ([ADR-0006](docs/decisions/0006-oidc-provider-choice.md)). The first administrator is created by an operator-only bootstrap.
- Every protected read or write checks the actor's current verified identity and explicit grant inside the same SQL statement. CI runs PostgreSQL integration tests, real Keycloak/Rauthy browser tests and disposable Compose restart checks.

**Not yet accepted:**

- **M1, hosted synthetic demo:** needs an operator-run, externally reachable HTTPS/OIDC installation, with witnessed browser, persistence and resource/cost evidence ([#27](https://github.com/dkoller/OpenClub/issues/27), [#28](https://github.com/dkoller/OpenClub/issues/28)). The repository ships only a [reference HTTPS overlay](docs/deployment/single-club.md#opt-in-public-https-reference-deployment-synthetic-data-only) and probes.
- **M2, real member data:** blocked on privacy/legal approval, the private-field contract, export/erasure/audit, backups and a witnessed recovery drill ([#12](https://github.com/dkoller/OpenClub/issues/12), [#13](https://github.com/dkoller/OpenClub/issues/13), [#26](https://github.com/dkoller/OpenClub/issues/26), [#180](https://github.com/dkoller/OpenClub/issues/180)). **Do not enter real personal data.**

The [roadmap](docs/roadmap.md#delivery-gates), the [M1 evidence checklist](docs/deployment/m1-resource-evidence.md) and the [open issues](https://github.com/dkoller/OpenClub/issues) are authoritative. Earlier dated README status notes are kept in [docs/history](docs/history/readme-status-2026-11-17.md).

## Goal

Help volunteers manage club administration without requiring expensive software or a large technical team. Build for one real club first while keeping the core concepts extensible to other sports and non-profit clubs.

## First vertical slice

The pilot administrator approved a first usable release demonstrating **member management, team assignments and configurable club/team-scoped permissions** in **German, English, French and Italian**. Members may be entered before login; invitations, so that members sign in themselves, are planned for the M1 demo ([design](docs/security/invitation-linking-design.md)) and are not implemented yet. The first existing system targeted for replacement is Excel membership administration.

Each independent installation owns its club's member data: no shared database, cross-installation data transfer, or automatic synchronization. First/last names and date of birth are required for all; adult email and phone required, optional for minors; player gender required only if configured by that club. The synthetic-data application enforces the required names, DOB, adult contacts and configured player-gender rule, and offers authorized member/team/permission workflows; this is **not** authorization to use real personal data. Private-field editing, lifecycle/retention, deployment and M2 legal/privacy approvals remain incomplete. See [accepted R01 pilot requirements](docs/concepts/product/r01-pilot-validation.md) and [R01/#10](https://github.com/dkoller/OpenClub/issues/10).

## Project documents

- [MVP and boundaries](docs/concepts/product/mvp.md)
- [Technical decision record](docs/decisions/0001-stack-selection.md)
- [Authentication architecture](docs/decisions/0003-oidc-session-proposal.md)
- [Optional local Keycloak setup](docs/development/keycloak-local.md)
- [Membership and privacy-related product decisions](docs/decisions/0004-member-policy.md)
- [Internal team roster privacy and authorization boundary](docs/security/team-roster-visibility.md)
- [Numbered roadmap, GitHub issues and completed milestones](docs/roadmap.md)
- [Single-club deployment and safe fresh-database setup](docs/deployment/single-club.md)
- [Opt-in synthetic HTTPS/OIDC demo reference](docs/deployment/single-club.md#opt-in-public-https-reference-deployment-synthetic-data-only) (requires an operator-provided host, DNS and HTTPS identity provider; not deployed by this repository)
- [Original milestone concept](docs/concepts/ai/first-milestone.md)
- [AI-assisted development concept](docs/concepts/ai/ai-agent-development.md)
- [Agent contribution rules](AGENTS.md)
- [Documentation maintenance for every PR](docs/contributing/documentation.md)
- [Security implementation and review checklist](docs/contributing/security-review.md)

## Contributing

Run locally with `docker compose up -d --build`; stop with `docker compose down` (data persists). See [cross-platform startup](docs/concepts/ai/one-command-run.md). For an opt-in, synthetic Keycloak login, follow [local provider setup](docs/development/keycloak-local.md) and operator-only first-club bootstrap; default Compose starts without authentication. For native development, use the checked-in `apps/api/gradlew` (or `gradlew.bat` on Windows) and `pnpm install --frozen-lockfile` in `apps/web`. Keep each PR at or below 5,000 changed lines, follow [agent rules](AGENTS.md), and update [affected documentation](docs/contributing/documentation.md) in the **same PR**.

## License

[MIT](LICENSE).
