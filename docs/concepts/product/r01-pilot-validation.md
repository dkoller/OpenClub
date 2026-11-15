# R01 — Pilot workflow validation

**Status:** Pilot administrator approved the synthetic requirements acceptance target on 2026-11-15; documentation review and R01 closeout still pending.
**Related:** [R01/#10](https://github.com/dkoller/OpenClub/issues/10), [ADR-0004](../../decisions/0004-member-policy.md), [R03/#12](https://github.com/dkoller/OpenClub/issues/12), [R04/#13](https://github.com/dkoller/OpenClub/issues/13)

## Purpose and evidence rules

Validate that one real floorball club administrator (or clearly identified representative) would use the proposed first workflow. This document is a **worksheet**, not evidence of an interview, pilot consent, product acceptance, or privacy clearance.

Record feedback without publishing names, contact details, real member records or identifying anecdotes. Store any private participant contact/consent records outside this repository.

## Product baseline — already decided by owner (not yet validated by pilot)

- Deployment-configurable club creation, with operator approval by default.
- Admin enters members **before** they have login accounts; invitations and verified account linking later.
- Initial ADR-0004 baseline: required **first name** and **last name**; optional **email** and **phone**. **Updated owner requirement (2026-11-15): date of birth must also be supported.** Whether date of birth is mandatory or optional remains undecided. Address and guardian fields are not approved.
- ADMIN manages membership and roles. Same-club authenticated directory access is proposed but requires privacy review, particularly for minors.
- Multi-club membership and cross-club isolation are mandatory.

These are summarized from ADR-0004. Do not turn provisional pilot assumptions into implementation requirements without recording stakeholder answers.

## Emerging product direction — owner feedback, 2026-11-15

The owner reports that the real floorball club uses multiple disconnected applications, including **Excel**, **WordPress**, **XPS**, **helfereinsatz.ch**, and additional tools not yet inventoried. Fragmentation across member administration, teams/coaching, roles and communication is the reported overarching pain point. The owner prefers **replacement rather than permanent integration**: OpenClub should be the shared authoritative source for club people, membership and organizational relationships, with cohesive modules for the other workflows.

This is **owner-reported discovery**, not an independently confirmed stakeholder interview. Do not claim the current application's detailed usage or duplicate-data flows are verified. A replacement roadmap is a long-term product direction, **not** an expansion of the R01 acceptance gate or the first membership MVP. Investigate data migration/export and switching costs before retiring an existing system; avoid assuming third-party exports are available.

| Existing application | Candidate replacement scope (to validate) |
| --- | --- |
| Excel | **First replacement chosen:** member and club administration; lists and imports still to validate |
| WordPress | Public club website and content |
| XPS | Team/coaching/training workflows |
| helfereinsatz.ch | Volunteer scheduling and shifts |
| Other applications | Inventory pending |

**First replacement selected (owner, 2026-11-15):** Excel membership and administration. **Explicitly deferred by owner:** Inventorying Excel columns, evaluating import requirements and specifying data migration. These are open questions, not accepted R01 requirements or prerequisites for continued discovery. Continue validation with the participating roles, preferred languages and a narrow synthetic acceptance journey without assuming that import is part of the first release.

## Pilot user groups — owner feedback, 2026-11-15

The owner identified six groups needing OpenClub access: **club board/committee**, **membership administrators**, **team managers/coaches**, **players/ordinary members**, **parents/guardians**, and **volunteers**. This is a stakeholder-reported *access audience*, not approval of six technical roles or a completed authorization design.

Proposed responsibilities to validate:
- Board/committee: governance and assigned administrative tasks, not blanket access to private data.
- Membership administrators: member and membership management.
- Team managers/coaches: assigned team and player management.
- Players/members: own profile and permitted club/team information.
- Parents/guardians: permissions for verified linked children, subject to minors' privacy review.
- Volunteers: view/manage own assignments; some may not be members.

A person may hold multiple functions; role labels must not imply unrestricted access. Keep existing MVP ADMIN/MEMBER authorization constraints unless separately reviewed through R04/R06. Guardian relationships, volunteer identities and detailed team permissions are future design questions, not approved MVP features.

**Owner direction (2026-11-15):** Member-record editing permissions should be **configurable and assigned by club administrators**, not permanently determined by organizational group. Treat organizational functions, capabilities and permission scopes as separate concepts. A person may have multiple functions; grants must be enforced server-side and scoped to authorized clubs/records. Design should prevent unauthorized privilege escalation and removal of the last authorized administrator. This is product direction, **not** an approved endpoint contract or implemented permission system. R04/R06/R10 need to reconcile it with the current ADMIN/MEMBER MVP baseline; avoid introducing granular roles prematurely.

**Owner direction (2026-11-15):** Grants must support **club-wide and team-specific scopes**. Individual-record permissions are not part of the currently selected model. Define capabilities independently from roles: e.g. viewing members, editing permitted member fields, changing team assignments, administering membership roles, and managing club settings. A team-scoped edit grant must not imply permission to change club membership, security roles or login identities. A person may be assigned to multiple teams; changes in team association must invalidate outdated access. The precise matrix is illustrative and remains subject to R04/R06 security review, tests and privacy requirements.

**Delegation decision (owner, 2026-11-15):** Only club administrators explicitly authorized to manage permissions may assign, modify or revoke grants. Authorization to manage members or teams does not itself imply authorization to manage permissions. Enforce delegation rights server-side; prevent unauthorized self-escalation and loss of the final authorized administrator. Exact bootstrap, grant and recovery semantics remain for R06/R07/R10 security design.

**Permission matrix sign-off: Pending.** Confirm which capabilities may be granted at team scope, who can add/edit/export member data, and which groups may see contact details. Do not assume minors' contact visibility is approved (R03).

## Stakeholder validation record

| Question | Response / evidence | Status |
| --- | --- | --- |
| Pilot organization or representative identified (use non-identifying reference only) | _Real floorball club confirmed by owner; club identity not recorded publicly_ | Owner-confirmed |
| Stakeholder role and relevant experience | _Owner reports direct involvement in club administration and can validate workflow_ | Owner-confirmed |
| Interview/review date and format | _2026-11-15: interactive owner/administrator discovery in chat; proposed acceptance script not yet formally reviewed or signed off_ | In progress |
| Current workflow (spreadsheet, chat, other) and top administrative pain point | _Owner reports fragmented Excel, WordPress, XPS, helfereinsatz.ch and other systems; independent pilot verification pending_ | Partially captured |
| When and by whom member records are first entered | _Not confirmed_ | Pending |
| Can members exist without accounts? | _Owner preference: yes; pilot unconfirmed_ | Pending |
| Minimum fields and why each is necessary | _Owner confirms date of birth is additionally needed; first/last name required, email/phone optional. Birth date requiredness and precise use/visibility remain undecided._ | Partially captured |
| Preferred UI language(s) for pilot | _Owner requires German, English, French and Italian in first release; default and language-switch behavior pending confirmation_ | Owner decision captured |
| Groups needing access | _Owner identified board, membership administrators, coaches, players, parents and volunteers; permission details pending_ | Partially captured |
| Need for invitations in first usable release | _Owner preference: later; pilot unconfirmed_ | Pending |
| Proposed acceptance flow reviewed | _Pilot club administrator explicitly approved the three-workflow synthetic acceptance scenario on 2026-11-15; implementation/demo not tested_ | Approved target |
| Stakeholder acceptance, requested changes and decision date | _2026-11-15: pilot club administrator approved the three-workflow synthetic acceptance scenario as initial requirements target; no changes requested in this approval_ | Requirements accepted |

## First usable release — owner-selected scope, 2026-11-15

The owner expects **all three** workflows in the first usable release: **member management**, **team assignments** and **configurable permissions**. This expands the earlier membership-only proposal. This is a **product acceptance target**, not a claim that the features exist or that the real pilot has accepted the demo. Refine R04/R06 and add appropriately scoped follow-up delivery work for teams and permission grants; do not silently force these features into existing R09/R10 tickets.

The first release must support German, English, French and Italian. Its initial data migration/import behavior and existing Excel column inventory remain explicitly deferred.

## Proposed acceptance scenario (synthetic only)

**Persona:** An authorized administrator for fictional club **Example Falcons**.

**Fixtures:** Fictional members **Alex Example**, **Sam Sample** and **Taylor Demo**; fictional teams **Falcons Juniors** and **Falcons Adults**. Use no real personal data.

1. Sign in as an authorized club administrator. Create Alex Example without a login account, using only first and last name; optionally capture synthetic contact details for Sam Sample.
2. List and search members, edit a member's permitted fields and archive a former member without deleting their identity indiscriminately. Verify ordinary members cannot make unauthorized changes.
3. Assign Alex Example to Falcons Juniors, move or assign a member to another team as permitted, and confirm a person may have multiple appropriate team relationships.
4. As an explicitly authorized permission administrator, grant a fictional coach a **team-scoped** member-edit capability for Falcons Juniors. Show that the coach can perform the permitted operation there but cannot edit Falcons Adults members outside that scope.
5. Demonstrate a **club-wide** grant on an authorized test account, revoke it and verify subsequent attempts are denied. Neither member/team administration nor an ordinary administrator role should automatically allow managing grants.
6. Using a second fictional club, verify tenant isolation for member lists, team assignments and permission grants. Verify no delegated capability permits privilege escalation or removing the final authorized administrator.
7. Demonstrate validation and denial errors without exposing private record details. Demonstrate the three workflows in **German, English, French and Italian**, including functional language switching if approved as the UI approach.

**R01 requirements acceptance (recorded 2026-11-15):** The owner stated direct involvement in the pilot club administration and explicitly approved this three-workflow synthetic scenario as the initial requirements acceptance target. This is administrator requirements approval, **not** a claim that the workflow has been implemented or demonstrated. **Release acceptance:** Separate actual implementation, security/privacy reviews, cross-club integration tests and browser verification. R01 approval alone does not authorize real member data.

## Questions to ask at the pilot review

1. Given that disconnected applications are the overarching problem, which single application/workflow should OpenClub replace **first**, and why? Walk through an example without sharing personal data.
2. Who creates records, and do people typically join the club before receiving an account?
3. Is date of birth mandatory for all members or optional? Which administrative tasks require the full date (versus age category), and who may view it? First/last names remain required; email/phone optional.
4. Which languages must the first pilot UI support?
5. The owner selected member management, team assignments and configurable permissions together: does this synthetic flow represent the first usable release, and what must change?
6. Do you handle minors? Who should be able to see their and guardians' contact data? Record concerns for R03, **not** a privacy sign-off.
7. Does the synthetic acceptance script reflect the task, and what would make you reject it?

## Decisions and hand-off

- **Validated pilot scenario:** Pilot club administrator explicitly approved the three-workflow synthetic requirements scenario on 2026-11-15. Runtime demonstration and release validation remain pending under R19.
- **Preferred pilot language(s):** German, English, French and Italian required for the first release (owner, 2026-11-15). Default language and switching behavior remain proposed, not accepted.
- **Changes to owner baseline:** Expanded first usable release to include team assignments and configurable club/team-scoped permissions; **date of birth added to requested member data** (requiredness pending). Detailed Excel inventory, import and migration requirements explicitly deferred.
- **Stakeholder acceptance (date, role, non-identifying reference):** 2026-11-15, participating pilot club administrator (owner-reported role), accepted the initial synthetic requirements target. No private identifying details recorded; independent implementation acceptance remains outstanding.
- **R03 hand-off:** Review contact visibility, **date of birth** minimization/access/retention (especially for minors), and data lifecycle before real records.
- **R04/R06/R10 hand-off:** Reconcile the expanded team and scoped-grant requirements with the contract and authorization model; create appropriately scoped feature issues instead of treating existing membership endpoints as sufficient.
- **R19 hand-off:** Run the full synthetic demo only after its implementation and release prerequisites.

## R01 completion checklist

- [x] Real pilot stakeholder or explicitly identified representative participated (owner-reported club administration involvement, 2026-11-15; no identifying details published).
- [x] Primary administrative pain point documented as fragmentation across Excel, WordPress, XPS, helfereinsatz.ch and others; detailed Excel workflow/inventory intentionally deferred.
- [x] Administrator accepted accountless entry in the synthetic scenario; invitations remain later by prior owner decision.
- [ ] First/last name required, email/phone optional; date of birth is additionally needed, but mandatory-versus-optional status and permitted uses/visibility still need confirmation. Four release languages accepted. Excel column inventory deferred.
- [x] Pilot administrator explicitly approved the three-workflow synthetic requirements scenario on 2026-11-15.
- [x] Privacy concerns linked to R03 and new team/scoped-permission contract and authorization work flagged for R04/R06/R10; detailed follow-up implementation issues still to be scoped.
- [ ] PR #72 human review/merge and R01 closeout decision recorded under issue #10.

**Testing:** Documentation-only change. No code or integration tests are claimed. Keep #10 open until evidence and human sign-off are documented.
