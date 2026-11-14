# R01 — First pilot workflow (discussion draft)

**Status:** Discovery only; **not validated with a real club**. Parent: [#10](https://github.com/dkoller/OpenClub/issues/10). Unresolved decisions: [#34](https://github.com/dkoller/OpenClub/issues/34).

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

## Decision log (not answered yet)

| Decision | Current state | Why it blocks downstream work |
| --- | --- | --- |
| Pilot partner and scenario | Unconfirmed | Prevents calling R01 done |
| Members before login | Unconfirmed | Impacts invitations, data model and API |
| Required personal fields | Display name only in current schema; not validated | Drives privacy and API schema |
| Sign-in provider | Unselected | R02 needs a decision before R05 |
| First-admin onboarding | Requires secure workflow | Prevents insecure club bootstrap |
| Language/device | Unknown | Affects frontend priorities |

## Boundaries

No interviews have been conducted through this task. Do not interpret this document as a stakeholder decision. Capture decisions against [#10](https://github.com/dkoller/OpenClub/issues/10), and create focused follow-up issues for changes outside the agreed MVP.
