# R01 — Pilot workflow validation

**Status:** Draft for stakeholder validation; not accepted.
**Related:** [R01/#10](https://github.com/dkoller/OpenClub/issues/10), [ADR-0004](../../decisions/0004-member-policy.md), [R03/#12](https://github.com/dkoller/OpenClub/issues/12), [R04/#13](https://github.com/dkoller/OpenClub/issues/13)

## Purpose and evidence rules

Validate that one real floorball club administrator (or clearly identified representative) would use the proposed first workflow. This document is a **worksheet**, not evidence of an interview, pilot consent, product acceptance, or privacy clearance.

Record feedback without publishing names, contact details, real member records or identifying anecdotes. Store any private participant contact/consent records outside this repository.

## Product baseline — already decided by owner (not yet validated by pilot)

- Deployment-configurable club creation, with operator approval by default.
- Admin enters members **before** they have login accounts; invitations and verified account linking later.
- Required **first name** and **last name**; optional **email** and **phone**. No birth date, address or guardian fields in the proposed MVP.
- ADMIN manages membership and roles. Same-club authenticated directory access is proposed but requires privacy review, particularly for minors.
- Multi-club membership and cross-club isolation are mandatory.

These are summarized from ADR-0004. Do not turn provisional pilot assumptions into implementation requirements without recording stakeholder answers.

## Stakeholder validation record

| Question | Response / evidence | Status |
| --- | --- | --- |
| Pilot organization or representative identified (use non-identifying reference only) | _Not recorded_ | Pending |
| Stakeholder role and relevant experience | _Not recorded_ | Pending |
| Interview/review date and format | _Not held_ | Pending |
| Current workflow (spreadsheet, chat, other) and top administrative pain point | _Not confirmed_ | Pending |
| When and by whom member records are first entered | _Not confirmed_ | Pending |
| Can members exist without accounts? | _Owner preference: yes; pilot unconfirmed_ | Pending |
| Minimum fields and why each is necessary | _Owner baseline: names required, email/phone optional; pilot unconfirmed_ | Pending |
| Preferred UI language(s) for pilot | _Not confirmed_ | Pending |
| Need for invitations in first usable release | _Owner preference: later; pilot unconfirmed_ | Pending |
| Proposed acceptance flow reviewed | _Not reviewed_ | Pending |
| Stakeholder acceptance, requested changes and decision date | _Not recorded_ | Pending |

## Proposed acceptance scenario (synthetic only)

**Persona:** An authorized club administrator managing a sample floorball club.

**Fixture:** Club **Example Falcons** and fictional members **Alex Example**, **Sam Sample**, **Taylor Demo**. No real member data or deliverable email addresses.

1. Given an operator-approved club and an authorized ADMIN, sign in through the supported OIDC flow.
2. Add Alex Example with a first and last name **without** inviting them or creating an account; contact fields can be omitted.
3. Add Sam Sample with optional synthetic contact fields.
4. List and search the club directory; confirm names and configured visibility are correct.
5. Assign or change an allowed club role as ADMIN; an ordinary member must not perform administrative changes.
6. Using a separate example club and identity, verify no cross-club member access or mutation.
7. Demonstrate validation errors with a missing required name and a denied action with an insufficient role.

**Acceptance check:** Stakeholder confirms whether this solves their real first administrative task, identifies any missing must-have behavior, and approves the priority and language for a future runnable demo. This is a *proposed script*, **not a completed demo or test result**. Runtime integration and privacy controls remain separately gated under R03/R06–R19.

## Questions to ask at the pilot review

1. What is the single most time-consuming or error-prone member administration task today? Walk through one example without sharing real personal data.
2. Who creates records, and do people typically join the club before receiving an account?
3. Which of first name, last name, email and phone are essential, optional or unnecessary? Why?
4. Which languages must the first pilot UI support?
5. Is adding, finding and changing the club role of a member enough for the first usable release? What is the one missing must-have, if any?
6. Do you handle minors? Who should be able to see their and guardians' contact data? Record concerns for R03, **not** a privacy sign-off.
7. Does the synthetic acceptance script reflect the task, and what would make you reject it?

## Decisions and hand-off

- **Validated pilot scenario:** Pending.
- **Preferred pilot language(s):** Pending.
- **Changes to owner baseline:** Pending.
- **Stakeholder acceptance (date, role, non-identifying reference):** Pending.
- **R03 hand-off:** Review contact visibility, minors and data lifecycle before real records.
- **R04 hand-off:** Freeze club/member API contract only after the pilot behavior is confirmed.
- **R19 hand-off:** Run the full synthetic demo only after its implementation and release prerequisites.

## R01 completion checklist

- [ ] Real pilot stakeholder or explicitly identified representative participated.
- [ ] Primary administrative pain point and current workflow documented.
- [ ] Accountless member entry and invitation timing verified.
- [ ] Necessary fields and pilot languages verified.
- [ ] Synthetic acceptance script approved or deviations recorded.
- [ ] Privacy-related concerns linked to R03; contract changes linked to R04.
- [ ] Human review and decision recorded under issue #10.

**Testing:** Documentation-only change. No code or integration tests are claimed. Keep #10 open until evidence and human sign-off are documented.
