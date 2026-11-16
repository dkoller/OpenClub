# OpenClub

An open-source project exploring a free-to-use platform for clubs, starting with sports clubs and a floorball-club use case.

**Status (2026-11-16):** OpenClub is a **single-club, synthetic-data-only pre-production application**. Local PostgreSQL, verified Keycloak/OIDC sessions, operator-only initial administrator bootstrap/recovery, and member/team/grant backend APIs and DE/EN/FR/IT React administration are implemented. An authorized administrator can create/search/edit/archive fictional members, create/rename/delete eligible teams, assign/unassign members, and delegate/revoke club- and team-scoped grants; all use explicit capability enforcement and current-session CSRF/Origin checks. Public active-member names show adults' full names and minors' first name plus surname initial, without private fields. The authorized club landing now displays its own name ([PR #207](https://github.com/dkoller/OpenClub/pull/207)). Disposable Keycloak/Chromium acceptance covers the main three workflows and four-language navigation ([PRs #200, #202, #206](https://github.com/dkoller/OpenClub/pull/206)); additional denied-user, input-validation and operational acceptance remains open.

**Not yet approved or shipped:** A publicly deployed TLS/OIDC demonstration, full policy settings and production-approved role-management workflows, complete private-field/API contracts, real-data privacy/legal sign-off, deletion/export/audit lifecycle, production backups and witnessed recovery. M1 (synthetic deployed demo) and M2 (real-club pilot) remain **unaccepted**. GitHub issues and [the milestone gates](docs/roadmap.md#two-delivery-milestones-and-critical-path-2026-11-16) track the remaining work.

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
- [Internal team roster privacy and authorization boundary](docs/security/team-roster-visibility.md)
- [Numbered roadmap, GitHub issues and completed milestones](docs/roadmap.md)
- [Single-club deployment and safe fresh-database setup](docs/deployment/single-club.md)
- [Original milestone concept](docs/concepts/ai/first-milestone.md)
- [AI-assisted development concept](docs/concepts/ai/ai-agent-development.md)
- [Agent contribution rules](AGENTS.md)
- [Documentation maintenance for every PR](docs/contributing/documentation.md)
- [Security implementation and review checklist](docs/contributing/security-review.md)

## Contributing

Run locally with `docker compose up -d --build`; stop with `docker compose down` (data persists). See [cross-platform startup](docs/concepts/ai/one-command-run.md). For native development, use the checked-in `apps/api/gradlew` (or `gradlew.bat` on Windows) and `pnpm install --frozen-lockfile` in `apps/web`. Keep each PR at or below 5,000 changed lines, follow [agent rules](AGENTS.md), and update [affected documentation](docs/contributing/documentation.md) in the **same PR**.

## License

[MIT](LICENSE).


## Deployment scope decision (2026-11-15)

OpenClub targets **one club per installation**, with an independently operated database and configuration; the same open-source code can serve different clubs via separate deployments. The fresh single-club schema and basic authenticated club lookup are implemented in [PR #79](https://github.com/dkoller/OpenClub/pull/79) under [accepted ADR-0005](docs/decisions/0005-single-club-installation.md). Old development database volumes are **not migrated**: reset only deliberately disposable pre-production data, as documented in the [deployment guide](docs/deployment/single-club.md). This is **not** production/privacy approval.

**Role-management increment (#211, synthetic M1):** Privileged administrators with a verified identity and explicit MANAGE_PERMISSIONS grant can read an active member's role and promote/demote through the DE/EN/FR/IT browser. Changes use the serialized last-admin-safe backend service, with strict Origin/CSRF checks, negative HTTP tests and Keycloak browser acceptance. This does not approve role management for real member data or bypass M2 audit/privacy gates.

**Player creation (synthetic M1, PR #213):** The authorized member-create form now supports a player checkbox and an optional free-text gender field (required in the form only when the installation's existing `gender_required_for_players` policy is enabled). The linked-member club read exposes that policy boolean, not any person's gender; member directory and public responses still contain names only. Real gender values, options, consent, retention and visibility remain R03/R04/M2 decisions.

**Synthetic player policy administration (PR #214):** A verified identity-bound administrator with explicit MANAGE_PERMISSIONS can toggle the existing player-gender requirement from the four-language club settings form. PostgreSQL prevents enabling a stricter rule when existing players lack the required field. No personal gender data is returned by the settings API; real-data policy and consent remain R03/R04/M2 gates.
