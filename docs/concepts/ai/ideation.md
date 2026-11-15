# AI ideation — OpenClub

> **Historical ideation, not current implementation instructions:** OpenClub now uses one club per independent installation and database, with no multi-tenant persistence or global person. See [accepted ADR-0005](../../decisions/0005-single-club-installation.md) and merged PR #79.

> Date: 13–14 November 2026  
> Source: ChatGPT project discussion. This is a **structured reconstruction**, not a verbatim transcript. Proposals are not decisions unless explicitly marked.

## Vision and scope

- Initial idea: a free application for one floorball club.
- Broaden the scope to **sports clubs and other non-profit/member-based organisations**.
- Potential working name: **OpenClub**.
- Keep the project free to use; explore open-source licensing and self-hosting.
- Build common capabilities with optional organisation-specific modules.
- Multi-organisation support should account for a person belonging to several clubs with distinct roles.
- Protect member data, particularly data about junior members.

## Candidate features

- Organisations, members, roles and permissions
- Teams/groups
- Events, calendar and attendance
- Communication and notifications
- Tasks, volunteers, equipment and resources
- Membership fees and basic administration
- Optional sports-specific modules: training, matches, competitions
- Branding and module configuration per organisation

## Infrastructure and distribution ideas

- Compare shared free hosting, self-hosted installations, and hybrid deployment.
- Investigate free tiers (e.g. Cloudflare Workers/D1 or Supabase/PostgreSQL), but verify limits and long-term sustainability.
- Consider a PWA and offline-first data via IndexedDB.
- Explore P2P, torrent and IPFS for **public, non-sensitive static files**, not private member records, permissions or transactional data.
- Keep tenant isolation, access control, backups, migrations and privacy central.

## GitHub and AI-assisted development

- GitHub and GitHub Copilot are assumed tools.
- Explore combining Copilot with Anthropic Claude Code, OpenAI Codex and potentially OpenHands.
- Consider bounded multi-agent workflows: architect/planner, implementer and independent reviewer.
- Agents should work on separate branches/worktrees, share architecture decisions and repository instructions, and submit pull requests.
- Suggested loop: issue → implementation → tests → review/security checks → fixes → PR → human approval.
- Avoid unbounded agent loops and unexpected model usage costs.
- Possible repository structure: `apps/`, `packages/`, `docs/`, `.github/`, `AGENTS.md`, tests and CI.

## Language and architecture discussion

### TypeScript frontend and backend

Pros discussed:
- One language across frontend, API and backend
- Shared contracts and tooling
- Large React ecosystem and strong AI coding assistance

Caveats:
- Static TypeScript types do not validate runtime input; use runtime validation.
- Keep business logic independent from UI frameworks.
- Database schema, migrations and authorization remain explicit.

### Kotlin frontend and backend

Candidate architecture:
- Kotlin/JS or Compose Multiplatform web frontend
- Ktor or Spring Boot backend
- Kotlin Multiplatform shared domain models, contracts or business rules
- PostgreSQL with multi-tenant data design

Tradeoffs:
- Kotlin is particularly compelling for backend modelling, coroutines and type safety.
- Kotlin/JS and Compose Multiplatform offer one-language opportunities, but have smaller web ecosystems than React/TypeScript.
- Compose Multiplatform becomes especially relevant for native Android/iOS ambitions.
- A pragmatic hybrid is **React/TypeScript frontend + Kotlin backend**.

## Open decisions

1. Web-first PWA or native mobile apps from the beginning?
2. Kotlin everywhere, TypeScript everywhere, or Kotlin backend + TypeScript frontend?
3. Shared hosted platform, self-hosting, or both?
4. Initial MVP features and target pilot organisation?
5. Open-source licence, governance and operational funding?
6. How much offline support and whether any P2P distribution is worthwhile?
7. Which AI agents/tools, budget ceilings and CI approval gates?

## Suggested next step

Choose the web/mobile strategy, then create a small proof of concept with organisation membership, roles, one event and attendance tracking. Evaluate development speed, deployment cost, privacy, and maintainability before committing to the full stack.
