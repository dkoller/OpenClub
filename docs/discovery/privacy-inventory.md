# R03 — Member data and privacy inventory

**Status:** Provisional discovery, **not legal approval**. Parent: [#12](https://github.com/dkoller/OpenClub/issues/12); unresolved decision issue [#36](https://github.com/dkoller/OpenClub/issues/36).

OpenClub starts with sports clubs; some pilot members may be minors. **Do not** seed, import or publish real membership data until the club's responsible administrator approves data handling and local law has been considered.

## Initial database inventory

| Table | Personal data | Need and access boundary | Lifecycle questions |
| --- | --- | --- | --- |
| `clubs` | Club name/slug (usually non-personal) | Public or authorized visibility to be agreed | Club closure/export |
| `persons` | Display name + stable UUID | Minimum viable member record; restrict via memberships | Duplicate resolution, deletion, correction |
| `person_identities` | Verified issuer/subject association | Login binding, not a public directory | Account unlink, provider migration |
| `memberships` | Club-person link, role, timestamp | Club-specific administration only | Leave club, historic retention, role audit |
| Future club-local personal fields | Names, date of birth, adult email + phone, optional player gender required by policy (not yet implemented) | R01 validated product need; minimize access and never share between clubs | Purpose, lawful basis, gender representation, retention, age threshold and minors' access |

## Decision checklist before handling live data

1. Identify the **controller** (which club or organization) and operational processor(s); document responsibilities.
2. Confirm applicable legal jurisdictions and draft an accessible privacy notice covering purpose, contact, recipients, hosting and rights.
3. Determine collection purpose and legal justification per data category; do not blindly assume all data always requires consent or that consent is never needed.
4. Specify **per-category retention and deletion triggers** rather than inventing a universal number of months/years.
5. Plan correct/edit, club-scoped export, erasure/anonymization and backups-expiry behavior; define what happens if a person belongs to several clubs.
6. Define least-privilege access: no cross-club directory, sensitive fields or administrative logs readable by unauthorized actors.
7. Consider minors, possible guardian consent/communications, photographs and other potentially sensitive sports data **only when requested**.
8. Decide incident handling, audit minimization and whether the use case requires a risk assessment / DPIA.

## Research note for Swiss associations

The [Swiss FDPIC's guidance for clubs and associations](https://www.edoeb.admin.ch/en/data-protection-in-clubs-and-associations) highlights purpose limitation, transparency and data minimization, and notes that ordinary membership-related processing does **not automatically require consent**. It also distinguishes photograph publication and explains that data should be erased or anonymized when no longer necessary unless retention obligations apply. This is guidance, **not** a substitute for assessing the actual club's jurisdiction and context.

## Explicit blockers

- Pilot requirements are accepted and [R01/#10](https://github.com/dkoller/OpenClub/issues/10) is closed; privacy and exact data handling are still unresolved under R03.
- Data subject rights, log retention, hosting and consent arrangements need review: [#36](https://github.com/dkoller/OpenClub/issues/36).
- R03 completion requires a responsible human review; this document only structures the work.

For implementation, add PostgreSQL-backed isolation tests and avoid real member data in CI or repository fixtures.

## Product decisions and outstanding privacy work (2026-11-14)

[ADR-0004](../decisions/0004-member-policy.md) specifies required first and last names, optional email/phone, and a **same-club directory visible to all authenticated members**. This is a product access decision, **not approval to publish all contacts for real users**, especially minors. Review user notice, applicable lawful basis, visibility restrictions for youth, possible per-club field access, controller responsibilities, and retention/erasure/backup policy before processing live records. Archive, export and configurable retention are incremental goals; exact retention periods remain undecided.


## Accepted R01 field and isolation requirements (2026-11-15; not privacy sign-off)

The historical `persons`/global person inventory above describes the *superseded foundation*, not an approved future data storage model. Under R01, a person's **club-local** name, date of birth, email, phone and gender must never be copied, exposed, auto-filled or synchronized across clubs, even if a shared login identity belongs to both. Review the global `person`/identity design through [#74](https://github.com/dkoller/OpenClub/issues/74) before extending member CRUD.

First name, last name and date of birth are required for every person in a club; **both email and phone required for adults**, optional minors; gender required for players only when explicitly enabled by their club. The age threshold, access-to-full-DOB/gender, age-based contact rationale, minor protections, data subject requests and retention must be reviewed under R03. The old 2026-11-14 note saying email/phone are optional for all people and DOB is unneeded is superseded; it is not current guidance. No real data should be loaded into unfinished club/member endpoints.


## Schema reset status (2026-11-15)

The initial table inventory above is **historical and superseded**. Fresh pre-production `V1__single_club.sql` defines exactly one `clubs` row per installation, independently scoped `accounts` and `account_identities`, optional account-linked `members` with first/last names and DOB, and OIDC transaction/session tables. No multi-club `persons` or `memberships` schema remains. `members` stores only this installation's club data, never globally reused profiles. Server-side policies and database triggers check both contacts for adults (provisional default age 18) and club-required player gender on writes. Age transitions, field access and legal approvals remain R03 work; the schema alone is not privacy clearance. See [ADR-0005](../decisions/0005-single-club-installation.md).
