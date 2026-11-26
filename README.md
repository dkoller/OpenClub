# OpenClub

An open-source project exploring a free-to-use platform for clubs, starting with sports clubs and a floorball-club use case.

## Current status (2026-11-26)

OpenClub is a **pre-production, synthetic-data-only** application. Each installation serves **one club** with its own PostgreSQL database ([ADR-0005](docs/decisions/0005-single-club-installation.md)). **Do not enter real personal data.**

**Works today**, with fictional data, in German, English, French and Italian:

- **Members:** search, create, edit every field, view details, archive with undo, find and reactivate archived members, download everything stored about a member as JSON for access requests, and import or export the member list as a CSV file for Excel (checked before anything is created; existing members are never changed). Erasing an archived member is an operator command (no button in M1, by owner decision).
- **Teams and permissions:** teams and assignments, member roles with last-administrator protection, club- or team-scoped permissions. Team managers read the contacts of their own team's active members, and every such read is logged.
- **Self sign-in:** an administrator sends a one-time invitation link and the member signs in and accepts. Unlinking ends their session at once ([design](docs/security/invitation-linking-design.md)).
- **My profile:** every member sees their own record (name, birth date, role, teams) and keeps their own email and phone up to date.
- **Trainings and events:** team managers plan trainings, matches and other events, also weekly; they cancel or delete single dates. Every member sees their teams' and their children's events on *Start* (what still needs an answer, one tap each) and *Schedule* (by day, filtered per child), and answers Yes, No or Maybe for themselves or their child until the start; the team's coaches see who comes, everyone sees the totals.
- **Coaches:** the *Team* page starts with the next two weeks and how many have not answered; each event lists names by answer (minors by first name and initial, absences without a reason), and team managers see attendance statistics for up to a year.
- **Match day and J+S:** coaches call up a squad with meeting time and place (each player sees only their own call-up), tick who actually came, and download the file for the J+S attendance import (NDS); editors keep each member's J+S person number.
- **Planning tools:** fields and halls with no double booking (everyone sees when each is booked), changing or cancelling many events at once with a preview and one summary email, and coach messages to everyone, the yes-sayers, those without an answer or the squad (sent by the server, no addresses shown).
- **Helper duty:** the treasurer sets a number of helper shifts per season and sees who still owes some; each family sees its own count.
- **Helpers and rides:** helper shifts per event with sign-up until the start, an overview of shifts that still need people, and an email a week ahead; carpooling where driver and booked passengers see each other's phone, and a free seat offered on *Start*.
- **Absences and calendar:** members (or parents for a child) enter days away, which count as *No*; a private calendar link shows one's own and the children's events in any phone calendar.
- **Join the club:** an optional public form for adults and parents; editors accept (creating the member) or decline.
- **Email and push notifications** (when the club configures its own SMTP server, push or both): changed or new events, announcements, shift reminders, *Helpers wanted*, missing answers, squad calls and coach messages, each with the member's own switch. Push sends only empty wake-ups; the phone fetches the text from the club's server.
- **Announcements:** the board (permission `POST_ANNOUNCEMENTS`) posts to the whole club, coaches to their team; members see what concerns them and their children, until the show-until date.
- **Membership fees:** a treasurer with the `MANAGE_FEES` permission creates fee periods for all active members, adjusts or excludes single members and marks payments by hand (tracking only, no payment processing). Members see *My fees*, guardians their children's. Once the treasurer has entered the club's account (IBAN or QR-IBAN, holder and address), members and guardians download a Swiss **QR bill** (A4 PDF) for each open fee under *Family → Fees*; the treasurer finds the payment reference of each member in the fee list to match the bank statement.
- **Guardians:** an administrator records up to two guardians for a minor. A guardian sees *My children* with the child's details and can update the child's email and phone. Access ends automatically at the club's adult age ([design](docs/security/guardian-design.md)).
- **Club settings:** name, adult age (13 to 25, default 18) and whether players need a gender. Public member lists show adults' full names and minors' first name plus surname initial, nothing private.
- **Layout:** one page at a time, organised by task: *Start* (what needs your answer), *Schedule*, *Family*, and *Team* or *Administration* only for people who can use them. Phone first, with the menu at the bottom of the screen and tables as cards; a computer shows the same pages with a sidebar ([guides](docs/guides/README.md#finding-your-way-everyone)). It can be added to a phone's home screen and then opens like an app. The Atkinson Hyperlegible font (SIL Open Font License, `apps/web/src/fonts/OFL.txt`) is served by the app itself, not by a font service.
- **Security:** login through Keycloak or Rauthy (one per installation, [ADR-0006](docs/decisions/0006-oidc-provider-choice.md)) with server-side sessions. Every protected read or write checks the actor's current verified identity and explicit grant in the same SQL statement, and changes are kept in an identifier-only audit trail. The HTTP API is the frozen [OpenAPI 1.33.1](docs/contracts/openapi-v1.yaml) contract (119 operations), guarded against drift in CI. CI also runs PostgreSQL integration tests and real Keycloak and Rauthy browser journeys.
- **Deployment:** one command on an Infomaniak VPS, driven by one settings file ([runbook](docs/deployment/infomaniak-demo.md)), with optional email, push and fictional demo data ([operator guide](docs/operations/operator-guide.md)).

**Not yet accepted:**

- **M1, hosted synthetic demo:** the owner runs the [deploy script and acceptance walkthrough](docs/deployment/m1-acceptance-walkthrough.md) on the real host ([#27](https://github.com/dkoller/OpenClub/issues/27), [#28](https://github.com/dkoller/OpenClub/issues/28)).
- **M2, real member data:** needs the controller's privacy and legal decisions, including the lawful basis for guardian access ([#12](https://github.com/dkoller/OpenClub/issues/12)), production backups and a witnessed recovery drill ([#180](https://github.com/dkoller/OpenClub/issues/180)).

How to use it: the [guides](docs/guides/README.md) for members and parents, coaches, the treasurer and administrators; running the server: the [operator guide](docs/operations/operator-guide.md). Authoritative status: the [roadmap](docs/roadmap.md#delivery-gates), the [decision log](docs/decisions/0008-owner-decisions-2026-11-18.md) and the [open issues](https://github.com/dkoller/OpenClub/issues). Earlier README status notes are kept in [docs/history](docs/history/readme-status-2026-11-17.md).

## Goal

Help volunteers manage club administration without requiring expensive software or a large technical team. Build for one real club first while keeping the core concepts extensible to other sports and non-profit clubs.

## First vertical slice

The pilot administrator approved a first usable release demonstrating **member management, team assignments and configurable club/team-scoped permissions** in **German, English, French and Italian**. Members may be entered before login and invited later to sign in themselves. Minors are not invited; their guardians are (see *Guardians* above). The first existing system targeted for replacement is Excel membership administration. By owner decision of 2026-11-19 ([ADR-0008 addendum 12](docs/decisions/0008-owner-decisions-2026-11-18.md)), features are built before pilot feedback, each after its own contract approval: My profile, trainings and events, attendance, membership fees, the Excel member list and announcements ([addenda 12–18](docs/decisions/0008-owner-decisions-2026-11-18.md)).

Each independent installation owns its club's member data: no shared database, cross-installation data transfer, or automatic synchronization. First/last names and date of birth are required for all; adult email and phone required, optional for minors; player gender required only if configured by that club. The synthetic-data application enforces the required names, DOB, adult contacts and configured player-gender rule; this is **not** authorization to use real personal data. Retention rules and the M2 legal and privacy approvals remain open. See [accepted R01 pilot requirements](docs/concepts/product/r01-pilot-validation.md) and [R01/#10](https://github.com/dkoller/OpenClub/issues/10).

## Project documents

- [MVP and boundaries](docs/concepts/product/mvp.md)
- [Technical decision record](docs/decisions/0001-stack-selection.md)
- [Authentication architecture](docs/decisions/0003-oidc-session-proposal.md)
- [Optional local Keycloak setup](docs/development/keycloak-local.md)
- [Membership and privacy-related product decisions](docs/decisions/0004-member-policy.md)
- [Internal team roster privacy and authorization boundary](docs/security/team-roster-visibility.md)
- [Numbered roadmap, GitHub issues and completed milestones](docs/roadmap.md)
- Privacy drafts for real data (#12): [controller sign-off sheet](docs/privacy/controller-decisions.md), [processing record](docs/privacy/processing-record.md), [privacy notice (DE)](docs/privacy/datenschutzerklaerung-entwurf.md)
- [Owner decisions of 2026-11-18](docs/decisions/0008-owner-decisions-2026-11-18.md), with the [privacy proposal](docs/discovery/privacy-proposal-2026-11-18.md), the [invitation design](docs/security/invitation-linking-design.md) and the [guardian design](docs/security/guardian-design.md)
- [Single-club deployment and safe fresh-database setup](docs/deployment/single-club.md)
- [Hosted demo on Infomaniak (runbook)](docs/deployment/infomaniak-demo.md) and the [M1 acceptance walkthrough](docs/deployment/m1-acceptance-walkthrough.md)
- [Guides for members and parents, coaches, the treasurer and administrators](docs/guides/README.md)
- [Operator guide](docs/operations/operator-guide.md): configuration, email and push, operator commands, housekeeping, backups and upgrades
- [HTTP API contract (OpenAPI 1.33.1)](docs/contracts/openapi-v1.yaml) and its [test coverage](docs/contracts/m1-route-test-coverage.md)
- [Original milestone concept](docs/concepts/ai/first-milestone.md)
- [AI-assisted development concept](docs/concepts/ai/ai-agent-development.md)
- [Agent contribution rules](AGENTS.md)
- [Documentation maintenance for every PR](docs/contributing/documentation.md)
- [Security implementation and review checklist](docs/contributing/security-review.md)

## Contributing

Run locally with `docker compose up -d --build`; stop with `docker compose down` (data persists). See [cross-platform startup](docs/concepts/ai/one-command-run.md). For an opt-in, synthetic Keycloak login, follow [local provider setup](docs/development/keycloak-local.md) and operator-only first-club bootstrap; default Compose starts without authentication. For native development, use the checked-in `apps/api/gradlew` (or `gradlew.bat` on Windows) and `pnpm install --frozen-lockfile` in `apps/web`. Keep each PR at or below 5,000 changed lines, follow [agent rules](AGENTS.md), and update [affected documentation](docs/contributing/documentation.md) in the **same PR**.

## License

[MIT](LICENSE).
