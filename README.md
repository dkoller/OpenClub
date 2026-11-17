# OpenClub

An open-source project exploring a free-to-use platform for clubs, starting with sports clubs and a floorball-club use case.

**Current engineering checkpoint (2026-11-17; `main` at `331f83e`, merged through #306):** The single-club synthetic member/team/grant workflows remain implemented. [#301](https://github.com/dkoller/OpenClub/pull/301) added Keycloak/Rauthy-aware external HTTPS discovery probing and guarded Compose resource samples; [#302](https://github.com/dkoller/OpenClub/pull/302) guards 21 registered M1 routes against OpenAPI drift; [#303](https://github.com/dkoller/OpenClub/pull/303) verifies keyboard navigation and member search in DE/EN/FR/IT in real Keycloak/Chromium; [#304](https://github.com/dkoller/OpenClub/pull/304) tests live role-grant/verified-identity revocation; [#305](https://github.com/dkoller/OpenClub/pull/305) proves a synthetic club UUID survives PostgreSQL/API/web restart in disposable Compose; and [#306](https://github.com/dkoller/OpenClub/pull/306) checks privacy-minimized Ktor response shapes. Combined `main` Build, Compose and M1 contract checks passed ([Actions](https://github.com/dkoller/OpenClub/actions)). **These are synthetic CI results, not externally hosted M1 acceptance, a 4 GB host-size guarantee, independent accessibility/security certification or M2 real-data approval.** See the [M1 evidence checklist](docs/deployment/m1-resource-evidence.md) and [roadmap](docs/roadmap.md).

**Latest authentication and recovery evidence (2026-11-17; merged through PR #298):** Rauthy 0.37.2 has independent synthetic real-HTTPS OIDC Chromium acceptance for password, WebAuthn passkey, password + WebAuthn, SMTP-delivered email OTP, signed token / actual JWKS rotation, offline provider-volume restore, lost-passkey administrator revocation and new-passkey registration (#283–#297). **Keycloak remains supported and tested separately**, configured as an alternative issuer per installation; there is no unreviewed provider migration. [PR #298](https://github.com/dkoller/OpenClub/pull/298) adds identity-verification, independent approval, private audit-record and witnessed-drill procedures, **not actual human attestations**. The provider's `LOCAL_TEST` and SMTP configuration are test-only. M1 hosted synthetic demo, 4 GB resource proof and M2 privacy/production/backup/operator sign-off remain open ([#11](https://github.com/dkoller/OpenClub/issues/11), [#27](https://github.com/dkoller/OpenClub/issues/27), [#28](https://github.com/dkoller/OpenClub/issues/28), [#180](https://github.com/dkoller/OpenClub/issues/180)). See [Rauthy evaluation](docs/development/rauthy-local.md) and [recovery procedure](docs/security/admin-recovery-procedure.md).

**Implemented application status (reviewed 2026-11-17):** OpenClub is a **single-club, synthetic-data-only pre-production application**. Local PostgreSQL, verified Keycloak/OIDC sessions, operator-only initial administrator bootstrap/recovery, and member/team/grant backend APIs and DE/EN/FR/IT React administration are implemented. An authorized administrator can create/search/edit/archive fictional members, create/rename/delete eligible teams, assign/unassign members, and delegate/revoke club- and team-scoped grants; all use explicit capability enforcement and current-session CSRF/Origin checks. Public active-member names show adults' full names and minors' first name plus surname initial, without private fields. The authorized club landing now displays its own name ([PR #207](https://github.com/dkoller/OpenClub/pull/207)). Disposable Keycloak/Chromium acceptance covers the main member/team/grant workflows and all four languages, including archive cancellation/confirmation and member-specific accessible prompts ([PRs #206, #231–#232](https://github.com/dkoller/OpenClub/pull/232)). Current authorization tests reject revoked verified identities for sessions, member/team operations, member directory and club metadata ([PRs #227–#230 and #233](https://github.com/dkoller/OpenClub/pull/233)); broader negative/accessibility and operational acceptance remain open.

**Historical browser-hardening checkpoint (PR #268, 2026-11-16; superseded by the current engineering and authentication summaries above):** [PR #268](https://github.com/dkoller/OpenClub/pull/268) adds expired-session role and permission browser regressions. Across [PRs #256–#268](https://github.com/dkoller/OpenClub/pull/268), synthetic CI now covers translated policy/member errors, permission bootstrap retry, fail-closed 401/403 private editors and canceled stale team roster reads. This is targeted Chromium/Keycloak and injected-denial evidence, **not** exhaustive accessibility/security certification or a deployed demonstration. The currently unfulfilled M1 gate is an operator-approved external HTTPS/OIDC demo with recorded synthetic-browser, persistence/restart and cost evidence; M2 real-data use remains separately prohibited pending legal/privacy and operational sign-off.

**Not yet approved or shipped:** A publicly deployed TLS/OIDC demonstration (only an optional operator-supplied HTTPS reference overlay and read-only probe exist), full adult-age/private-field settings and complete approved API contracts, real-data privacy/legal sign-off, deletion/export/audit lifecycle, production backups and witnessed recovery. Synthetic role-management workflows exist but have no M2 production approval. M1 (synthetic deployed demo) and M2 (real-club pilot) remain **unaccepted**. GitHub issues and [the milestone gates](docs/roadmap.md#two-delivery-milestones-and-critical-path-2026-11-16) track the remaining work.

## Goal

Help volunteers manage club administration without requiring expensive software or a large technical team. Build for one real club first while keeping the core concepts extensible to other sports and non-profit clubs.

## First vertical slice

The pilot administrator approved a first usable release demonstrating **member management, team assignments and configurable club/team-scoped permissions** in **German, English, French and Italian**. Members may be entered before login; invitations come later. The first existing system targeted for replacement is Excel membership administration.

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


## Deployment scope decision (2026-11-15)

OpenClub targets **one club per installation**, with an independently operated database and configuration; the same open-source code can serve different clubs via separate deployments. The fresh single-club schema and basic authenticated club lookup are implemented in [PR #79](https://github.com/dkoller/OpenClub/pull/79) under [accepted ADR-0005](docs/decisions/0005-single-club-installation.md). Old development database volumes are **not migrated**: reset only deliberately disposable pre-production data, as documented in the [deployment guide](docs/deployment/single-club.md). This is **not** production/privacy approval.

**Role-management increment (#211, synthetic M1):** Privileged administrators with a verified identity and explicit MANAGE_PERMISSIONS grant can read an active member's role and promote/demote through the DE/EN/FR/IT browser. Changes use the serialized last-admin-safe backend service, with strict Origin/CSRF checks, negative HTTP tests and Keycloak browser acceptance. This does not approve role management for real member data or bypass M2 audit/privacy gates.

**Player creation (synthetic M1, PR #213):** The authorized member-create form now supports a player checkbox and an optional free-text gender field (required in the form only when the installation's existing `gender_required_for_players` policy is enabled). The linked-member club read exposes that policy boolean, not any person's gender; member directory and public responses still contain names only. Real gender values, options, consent, retention and visibility remain R03/R04/M2 decisions.

**Synthetic player policy administration (PR #214):** A verified identity-bound administrator with explicit MANAGE_PERMISSIONS can toggle the existing player-gender requirement from the four-language club settings form. PostgreSQL prevents enabling a stricter rule when existing players lack the required field. No personal gender data is returned by the settings API; real-data policy and consent remain R03/R04/M2 gates.
