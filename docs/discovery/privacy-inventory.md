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
| Future contact fields | Not collected today | Do not add until pilot validates need | Consent/notification/guardian rules |

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

- Pilot workflows and minimum fields unresolved: [#10](https://github.com/dkoller/OpenClub/issues/10).
- Data subject rights, log retention, hosting and consent arrangements need review: [#36](https://github.com/dkoller/OpenClub/issues/36).
- R03 completion requires a responsible human review; this document only structures the work.

For implementation, add PostgreSQL-backed isolation tests and avoid real member data in CI or repository fixtures.
