# R01 — First pilot workflow (discussion draft)

**Status:** Historical discovery worksheet written before pilot acceptance. **R01/#10 is now closed** after direct pilot administrator approval and merged PRs #72–#73. Original questions below are archival; current requirements are in [the accepted R01 worksheet](../concepts/product/r01-pilot-validation.md).

## Proposed smallest demo

A volunteer signs in, creates or selects an authorized club, adds a person with a display name, grants a club-specific role, searches members and switches to a second club without seeing the first club's records. Sample data only; no real member information. This matches [MVP proposal](../concepts/product/mvp.md).

### Candidate acceptance stories to confirm with pilot

1. An authorized first administrator can create a club and obtain its first ADMIN role via a protected bootstrap flow.
2. The administrator can add a member **without requiring a login**, if the pilot confirms that workflow.
3. A person can belong to two clubs with distinct roles; permissions are checked per club.
4. An ordinary member cannot add other people or change roles.
5. Searching and switching clubs never reveals another club's data.
6. A second authorized admin can take over without losing access (last-admin safeguard).
7. The pilot can export or request deletion under an agreed data-handling policy.

## Interview / acceptance checklist

- [ ] Which volunteer role performs this task, and how often?
- [ ] What spreadsheet/messages does the current workflow replace?
- [ ] Is a member record created before signup, or does the member accept an invitation first?
- [ ] Is display name sufficient for the first iteration? Do *not* collect addresses, dates of birth or contacts by default.
- [ ] Are youth members involved? Who may view/manage their records? Any guardian-related needs?
- [ ] Required UI languages and preferred devices (mobile/desktop)?
- [ ] How does a club get its first administrator? Who can transfer administrative responsibility?
- [ ] What is acceptable as a successful first 10-minute demonstration?
- [ ] What data must be exported, corrected, deleted or retained?
- [ ] Who will review the prototype and provide acceptance feedback?

## Original decision log (historical; superseded where answered in R01)

| Decision | Current state | Why it blocks downstream work |
| --- | --- | --- |
| Pilot partner and scenario | Unconfirmed | Prevents calling R01 done |
| Members before login | Unconfirmed | Impacts invitations, data model and API |
| Required personal fields | Display name only in current schema; not validated | Drives privacy and API schema |
| Sign-in provider | Unselected | R02 needs a decision before R05 |
| First-admin onboarding | Requires secure workflow | Prevents insecure club bootstrap |
| Language/device | Unknown | Affects frontend priorities |

## Boundaries

At the time this original worksheet was written no interviews had been conducted. Subsequently a real pilot club administrator directly validated the synthetic requirements scenario on 2026-11-15. Do not treat this historical draft alone as the accepted requirements. Capture decisions against [#10](https://github.com/dkoller/OpenClub/issues/10), and create focused follow-up issues for changes outside the agreed MVP.

## Product choices recorded 2026-11-14 (still requires pilot validation)

The project owner chose configurable new-club creation (approval by default), manual member entry first with later invitations, required first and last name, optional email and phone, and authenticated **same-club** directory/contact visibility for both ADMIN and MEMBER roles (writes restricted to ADMIN). Export, archive and deletion will be delivered incrementally. See [ADR-0004](../decisions/0004-member-policy.md). These choices do **not** assert that a real pilot has validated the workflow.


## Accepted pilot supersession (2026-11-15)

The first release now targets Excel membership administration, **member management + team assignments + configurable club/team-scoped grants**, in German, English, French and Italian. Member records can exist without login. First name, last name and date of birth are mandatory for all; email and phone mandatory for adults, optional minors; gender required for players only when configured by the club. **Personal fields remain local to each club** even if one verified identity has memberships in several clubs. The old table's unconfirmed pilot/member/provider/language entries are superseded. Requirements were accepted, not implemented; R03/R04/R06/R19 remain open. Excel columns/import/migration remain deferred.


## Single-club architecture correction (2026-11-15)

The owner subsequently selected exactly **one club per independent installation**, not a two-club login/switching journey. The earlier scenario and multi-club paragraphs are historical. The accepted fields, member/team/permission workflows and four languages remain, but a second-club creation attempt must be rejected within one installation. This is tracked in [ADR-0005](../decisions/0005-single-club-installation.md), #74 and the R19 synthetic demo; no real data should be used.
