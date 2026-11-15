# OpenClub MVP proposal

**Status:** Historical MVP proposal (14 November 2026). First-release requirements and one-club-per-installation architecture accepted 15 November 2026 supersede scope below. See [pilot worksheet](r01-pilot-validation.md).

## Problem

Club volunteers often coordinate member details, roles and basic administration in ad hoc spreadsheets and messages. OpenClub should make this manageable without a commercial subscription or complicated setup.

## Initial audience

One real floorball club, with an architecture that can support multiple sports clubs. Supporting all nonprofit types is an eventual possibility, not an MVP commitment.

## First end-to-end use case

**As a club administrator, I want to manage members and their club roles, so I can maintain an accurate member list.**

Proposed flow:
1. Create a club with its name and short identifier.
2. Create or invite a person as a member of that club.
3. Assign or change their role within the club.
4. List and search club members.
5. Sign in as a different club's administrator and verify there is no access to the first club's members.

## Acceptance criteria

- Every club has a stable ID and owns its own memberships.
- A person can belong to multiple clubs without merging their club-specific roles.
- Club administrators can view/manage memberships **only** within their authorized club.
- Ordinary members cannot administer other members.
- Invalid inputs receive comprehensible error messages.
- The first slice has repeatable automated tests, including cross-club access denial.
- Deployment and running costs are documented; the project can be self-hosted without mandatory proprietary services.

## Minimal domain vocabulary

- **Organization / club:** Boundary for administration and access control.
- **Person:** A human identity (not necessarily tied to exactly one club).
- **Membership:** Relationship between a person and one club.
- **Role assignment:** Authority of a person within a club, not a global superuser.

Consider sport-specific domain objects (teams, schedules, competitions) only after the core club/membership foundation works.

## Explicitly out of the first slice

Payments, accounting, event scheduling, tournaments, live scores, federation integrations, push notifications, chat, file sharing, agent orchestration as a product feature, and general nonprofit workflows.

## Open questions before implementation

1. Is the first club a real pilot partner, and what is its most urgent administration problem?
2. Should a club administrator invite existing users, or enter members before they create an account?
3. What personal data is genuinely required for the first use case?
4. How will consent, retention, data export and deletion work?
5. Which stack best fits contributors and zero/low-cost hosting?

Avoid implementing speculative requirements until these choices are reviewed.


## Accepted pilot direction (2026-11-15; supersedes earlier proposal where inconsistent)

The pilot floorball club uses disconnected Excel, WordPress, XPS, helfereinsatz.ch and other software. OpenClub aims to **replace** these tools progressively and be the source of truth **within each club**, rather than act as an integration hub or use a shared cross-club directory. Excel membership/administration is the first replacement; spreadsheet field inventory and migration/import behavior are deferred.

The initial usable release must demonstrate member creation/search/edit/archive, team assignments and configurable **club/team-scoped** permission grants, in German, English, French and Italian. Club administrators explicitly authorized for permission management alone can grant/revoke capabilities. Personal data must **never be shared between clubs**, even for one authenticated identity. Names and date of birth are mandatory for all; both phone and email mandatory for adults, optional for minors; gender mandatory for players only if their club requires it. Adulthood threshold, privacy for minors and details of technical storage/authorization require follow-up design under R03/R04/R06. The pilot administrator accepted this *synthetic requirements scenario*, not an implemented release; see [R01/#10](https://github.com/dkoller/OpenClub/issues/10) and [R19/#28](https://github.com/dkoller/OpenClub/issues/28).


## One club per installation (accepted 2026-11-15)

Each independently operated OpenClub deployment holds exactly one club, with its own PostgreSQL database and local account/member records. Global persons, multi-club memberships, club switching and cross-tenant APIs in the original proposal are **not** first-release features. Member management, multiple team assignments and configurable club/team permission grants remain required, in DE/EN/FR/IT. First name, last name and DOB are mandatory; adult email plus phone and configured player gender are mandatory. See [ADR-0005](../../decisions/0005-single-club-installation.md) and [R19](https://github.com/dkoller/OpenClub/issues/28).
