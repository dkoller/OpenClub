# OpenClub MVP proposal

**Status:** Proposal for discussion, 14 November 2026.

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
