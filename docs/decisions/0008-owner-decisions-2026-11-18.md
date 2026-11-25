# ADR-0008: Owner decisions on M2 contract, recovery, member sign-in, privacy proposal and accessibility audit

- **Status:** Accepted by the repository owner on 2026-11-18. Each decision was asked one at a time and answered by the owner; the wording of what each answer does **not** approve is the agent's, and the owner may correct it.
- **Scope:** Delivery gates and product scope. This is **not** legal approval and does **not** authorize real member data, a production deployment, spending or any security or privacy exception.

## Decisions

| # | Topic | Decision | Issue |
| --- | --- | --- | --- |
| 1 | Privacy, retention and data lifecycle | The owner asked the agent to **draft a proposal**. It is drafted in [privacy proposal 2026-11-18](../discovery/privacy-proposal-2026-11-18.md) and is **not yet decided**: every value in it is a proposed default for the owner and the controller to approve or change. | [#12](https://github.com/dkoller/OpenClub/issues/12) |
| 2 | M2 contract draft | The owner **approved [`openapi-m2-draft.yaml`](../contracts/openapi-m2-draft.yaml) as is** (full confidential member replacement and HTTP reactivation, still `0.0.0-draft` and not implemented). This approves the draft as the target contract. It does **not** approve real data, release or any implementation: the M2 prerequisites already recorded in [ADR-0007](0007-owner-decisions-2026-11-17.md) (privacy decisions, audit #26, security review) still apply. A later change to the frozen M1 surface still needs a new owner-approved version. The draft contains only these two operations: it has **no** contact-read, birth-date-read, export or erasure operation, so those would need a draft extension that the owner approves (see the privacy proposal, section 6). | [#13](https://github.com/dkoller/OpenClub/issues/13) |
| 3 | Sole-administrator recovery | The owner **approved the documented procedure as written** ([admin-recovery-procedure.md](../security/admin-recovery-procedure.md) and the [case template](../security/admin-recovery-case-template.md)). This approves the written procedure only. **No witnessed drill has been performed**, so recovery evidence for real data (M2) is still missing. | [#180](https://github.com/dkoller/OpenClub/issues/180) |
| 4 | Members signing in themselves | The owner decided members **must be able to sign in themselves for the pilot**. Invitations and verified account linking (previously deferred) are therefore in scope. A design is drafted in [invitation and account-linking design](../security/invitation-linking-design.md) for approval **before any code**. Whether the hosted M1 synthetic demo must include it is **not decided**; until it is, M1 acceptance still rests on administrator-managed accountless members. | [#20](https://github.com/dkoller/OpenClub/issues/20) |
| 5 | Third-party security review | The owner reports that an independent third-party security review was done and found nothing worth fixing. Reviewer: **secureIt AG**; date: **2026-11-18**, as given by the owner. Only that statement is recorded: the report is not in the repository and was not read by the agent, and the scope of the review was not provided and is therefore **not recorded**. | [#24](https://github.com/dkoller/OpenClub/issues/24) |
| 6 | Manual accessibility audit | The owner decided to **skip** a manual screen-reader, focus-order and contrast audit. The accepted evidence is the automated browser checks only (one `main`, named regions, document `lang`, accessible control names, resolving ARIA references, keyboard-only member search and archive cancel). This is **not** an accessibility conformance claim. | [#25](https://github.com/dkoller/OpenClub/issues/25) |

## Consequences

- **M1 is still blocked** on the hosted demo: operator and OIDC provider are not chosen ([ADR-0007](0007-owner-decisions-2026-11-17.md), decisions 1a and 1c).
- **Not started:** any implementation of the M2 draft operations or of invitations. The proposals need the owner's approval of the values and design first.
- **Still open for M2:** controller, jurisdiction and lawful basis, retention values, notices, minors' safeguards, export/erasure/audit (#26), backups, a witnessed recovery drill and operator sign-offs.

## Addendum — later on 2026-11-18: approval of the proposed defaults

After the two drafts were merged, the owner said: **"Approve all defaults, start the audit table."** The agent records the scope of that approval as follows; the owner may correct it.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 7 | [Privacy proposal](../discovery/privacy-proposal-2026-11-18.md), section 7 | The owner **approves the proposed default** for every row that has one: contact visibility (3), minors' contacts (4), archived retention of 24 months then erase (5), erase as hard delete after archive (6), 30-day request deadline (7), 30-day rolling backups (8), 12-month audit retention with no personal data in events (9) and deleting accounts without a login after 90 days (**later changed to 2 years, decision 14**) (10). **Not approved, because the proposal gives no default and they are facts or legal questions for the controller:** jurisdiction, controller, processors and hosting location (1), and lawful basis and notices (2). The values above are the owner's product defaults; the controller's approval is still required before any real member data. |
| 8 | [Invitation design](../security/invitation-linking-design.md), "Decisions needed" | The owner **approves the proposed default** for invitation life of 7 days (2), who may invite, revoke and unlink (3), administrator confirmation after acceptance for minors only (4) and hand-over of the link by the administrator with no system email (6). **Still undecided, because they carry no default:** whether invitations belong in the hosted M1 demo or only the pilot (1), minors signing in themselves versus a guardian (5), and approval of a new contract version for the four operations (7). No invitation code is written before (7). |
| 9 | Audit table | The owner asked the agent to start with the audit table (privacy proposal, section 8, step 2). It is implemented in Flyway V12 and wired into the existing member, role, grant and recovery operations; see [club-membership-access.md](../security/club-membership-access.md). |

**Not changed by this addendum:** no real data is authorized; the controller's decisions, a new contract version for contact reads, export, erasure and invitations, and the remaining implementation steps (archive-then-erase, export, contact reads, retention jobs, invitations) are still to be approved and built one pull request at a time.

## Addendum 2 — later on 2026-11-18: invitations in the M1 demo, guardians, contract draft

Asked one set of questions at a time, the owner answered as follows. The agent's wording of consequences may be corrected by the owner.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 10 | Invitations in the hosted M1 demo ([invitation design](../security/invitation-linking-design.md), decision 1) | **Included.** The M1 synthetic demo must show a second synthetic user accepting an invitation. This changes the earlier statement that M1 rests on administrator-managed accountless members. M1 acceptance still waits on the operator and provider (ADR-0007, 1a and 1c). |
| 11 | Who signs in themselves (decision 5) | **A guardian signs in for a minor.** The privacy proposal excludes guardian relationships until a separate requirement and privacy review, so a **guardian design is drafted and reviewed before any code** that lets someone act for a minor. Adult members signing in themselves (the design as written) is not blocked by this. |
| 12 | Contract extension (decision 7) | **The agent may draft an unversioned extension of the M2 draft and build against it.** The owner approves the contract text in the pull request; the agent merges code for an operation only after that approval is recorded there. The frozen 1.1.0 M1 surface changes only with a new owner-approved version. |
| 13 | Retention jobs | Implemented (V13, `RetentionCleanup`): audit events older than 12 months and accounts with no linked member and no login for **2 years** (changed from 90 days by decision 14) are deleted in bounded hourly batches. The controller's approval is still required before real data. |
| 14 | Orphan-account retention | The owner said: **"Not a 90 day inactivity rule, make it 2 years."** The rule for accounts with no linked member is therefore **2 years without a login**, replacing the 90-day default the owner approved in decision 7 (row 10). The earlier text is kept as the historical record. |

## Addendum 3 — contract extension drafted (awaiting approval)

Following decision 12, the agent drafted the extension in [`openapi-m2-draft.yaml`](../contracts/openapi-m2-draft.yaml): `getLocalMemberDetails`, `eraseArchivedLocalMember`, `createLocalMemberInvitation`, `revokeLocalMemberInvitation`, `acceptInvitation` and `unlinkLocalMemberAccount`, all marked NOT IMPLEMENTED. **It is a proposal: no code for any of these operations is merged until the owner approves the text in the pull request and the approval is recorded here.** A guardian design for review is in [guardian-design.md](../security/guardian-design.md); guardians are not part of this extension.

## Addendum 4 — contract text approved, guardian design privacy-reviewed

The owner replied on pull request #338: **"Approved. And privacy review ok."** The agent records the scope as follows; the owner may correct it.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 15 | Contract extension text | The owner **approved the six operations as drafted** in [`openapi-m2-draft.yaml`](../contracts/openapi-m2-draft.yaml): `getLocalMemberDetails`, `eraseArchivedLocalMember`, `createLocalMemberInvitation`, `revokeLocalMemberInvitation`, `acceptInvitation` and `unlinkLocalMemberAccount`, including the details the agent listed: an unreadable field is absent and null means "readable and not set", 409 when an invitation is already open or the member is linked, one generic 400 `invalid` for every acceptance failure, and the new audit events (`CONTACT_READ`, `INVITATION_CREATED`, `INVITATION_ACCEPTED`, `INVITATION_REVOKED`, `ACCOUNT_UNLINKED`). Code for each operation may now be written and merged. This is a contract approval, not an approval of real data. |
| 16 | Contract version | The agent proposed that operations leave the draft and join the frozen surface as **OpenAPI 1.2.0** when implemented, and the owner approved ("Approved") the message that contained this proposal. Each implementing pull request moves its operations from the draft into `openapi-v1.yaml`, bumps the version once per release of the surface, and updates the drift check. Reactivation and member replacement (`PUT`) stay in the draft: replacement still needs the detail read in use first. |
| 17 | Guardian design | The owner's **privacy review of the [guardian design](../security/guardian-design.md) is OK**: its proposed defaults (decisions 1 to 6 in that document) stand as the owner's product decisions. **Not covered, because it is for the controller and counsel:** the lawful basis and notices for a guardian's access to a child's data (point 1 of its privacy list) and the other open privacy points the design assigns to them. A guardian contract extension (operations, schema) is still to be drafted and approved before any guardian code, as the design's order of work says. No real data is authorized. |

**Consequences:** the invitation slice, unlink, the restricted detail read and erasure over HTTP are unblocked and are built one pull request at a time. Guardian operations still need their own contract draft.


## Addendum 5 — full member edit and reactivation approved (2026-11-19)

Asked in the Claude Code session that built pull request #342 whether `replaceLocalMemberDetails` (`PUT /v1/members/{memberId}`) and `reactivateLocalMember` (`POST /v1/members/{memberId}/reactivate`) may leave the draft, the owner chose **"Approve both"**. In the same session the owner decided that **erasure has no browser UI in M1** (the operator command stays the M1 erasure path) and asked for larger pull requests that bundle several steps.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 18 | Full member edit | Approved as drafted: an editor with `EDIT_MEMBERS` replaces every editable field of an active member; omitted contacts and gender clear them, so the browser reads the current values through the restricted detail read first. Database rules stay authoritative (400). |
| 19 | Reactivation | Approved as drafted: restores only active status, never grants, teams or a login link; the record is validated against the current club policy (409 if it no longer fits); audited as the new `MEMBER_REACTIVATED` event (V18). |
| 20 | Contract version | Both join the frozen surface as **OpenAPI 1.4.0**; the M2 draft keeps no operation for now. |
| 21 | Erasure UI | None in M1; the HTTP route serves API clients and the operator command remains the M1 path. An archived-member listing would be a new contract change. |

None of this authorizes real data.

## Addendum 6 — hosting provider and backups for the synthetic demo (2026-11-19)

In the Claude Code session after pull request #343 the owner stated: **"The hosting provider is Infomaniak"** and **"No backup for now."**

| # | Topic | Recorded decision |
| --- | --- | --- |
| 22 | Hosting provider for the M1 demo | **Infomaniak** (Switzerland). The agent recommends VPS Lite 2 vCPU / 4 GB / 60 GB at CHF 7.20 per month (price checked 2026-11-19), inside the ≤ €10/month of ADR-0007; the invoiced amount is the evidence, not this estimate. Who operates the host is still open. |
| 23 | Backups | **None for the synthetic demo for now.** Losing the server loses the fictional demo data. This does not change the M2 gates: real member data still needs backups, a witnessed restore and recovery sign-off (#12, #26, #180). |
| 24 | Identity provider on the host | Not decided by the owner. The [Infomaniak runbook](../deployment/infomaniak-demo.md) uses **Rauthy** on the same VPS because it fits in 4 GB next to the API and PostgreSQL; Keycloak stays supported (ADR-0006) with an external or larger host. |
| 25 | Demo email and tooling | Asked to bundle a one-command deploy, fictional demo data and email, the owner answered **"Go with email"**: Rauthy sends email through an Infomaniak mailbox (optional overlay, configured on the host; credentials never in the repository). |

None of this authorizes real data.

## Addendum 7 — archived-member search (2026-11-19)

Asked in the Claude Code session whether `GET /api/v1/members/archived` (name search over archived members for `EDIT_MEMBERS`, items: id, names and archive time only) may be added as OpenAPI 1.5.0, the owner chose **"Approve"**; asked whether that list should also offer *Erase permanently*, the owner chose **"Reactivate only"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 26 | Archived-member search | Approved as OpenAPI **1.5.0**: editors find members archived earlier; no birth dates, contacts or account data in the list (the restricted detail read serves editors those). |
| 27 | Erasure UI | Confirms decision 21: the *Archived members* panel offers **Reactivate only**; erasure stays an operator command in M1. |

None of this authorizes real data.

## Addendum 8 — guardian contract approved and built (2026-11-19)

Asked in the Claude Code session to approve the six guardian operations drafted from the privacy-reviewed [guardian design](../security/guardian-design.md), the owner chose **"Approve, build it"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 28 | Guardian operations | Approved as drafted and implemented as OpenAPI **1.6.0**: editors (`EDIT_MEMBERS`) list, add (their attestation, at most two, active adults with a login) and remove a minor's guardians; a guardian lists their minors, reads one (names, birth date, email, phone, player flag, team names; audited) and replaces only email and phone. No capability is granted; access ends at the club's adult age, on archive, unlink, erasure or removal. |
| 29 | Still open | Lawful basis and notices for a guardian's access to a child's data (controller and counsel). This approval covers synthetic data only. |

## Addendum 9 — demo operator (2026-11-19)

Asked who operates the Infomaniak demo server (orders the VPS, holds root and the generated secrets, runs the deploy script), the owner answered **"Me (the owner)"**; the two hostnames are **not decided yet**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 30 | Demo operator | **The owner** operates the synthetic demo host and runs the [M1 acceptance walkthrough](../deployment/m1-acceptance-walkthrough.md). This replaces "operator not yet chosen" in ADR-0007 and decision 22. |
| 31 | Hostnames | Open; the runbook keeps `club.example.ch` and `id.example.ch` placeholders. |

## Addendum 10 — member export for editors (2026-11-19)

Offered "the Invitations panel fix" and "proposing the editor export for #26 for your approval", the owner answered **"Ok, lets implement them all"**, which the agent recorded as approval of the export as proposed.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 32 | Member export | Approved as OpenAPI **1.7.0**: `GET /v1/members/{id}/export` for `EDIT_MEMBERS` returns the same complete export as the operator command (own fields, teams, invitation times, grants, guardianships with the other member as an ID only; never the login binding, sessions, other people's names or the audit trail), as a JSON download, audited as `MEMBER_EXPORTED` with the editor as actor. For access and portability requests; retention and notices stay with #12. |
| 33 | Editor-only panels | *Invitations* and *Guardians* render only for members who can use them (editors; for *Invitations* also permission managers, who unlink). |

## Addendum 11 — conditional member replacement (2026-11-19)

An independent security review of PRs #342–#351 noted that `PUT /v1/members/{id}` had no `If-Match`, so an editor's
stale form could silently overwrite a guardian's contact update. Asked whether to add the version check (a contract
change), the owner answered **"Yes"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 34 | Conditional replacement | Approved as OpenAPI **1.8.0**: every member row has a version (V21) that every change advances. The editor's `GET /v1/members/{id}/details` returns it as a strong `ETag`; `PUT /v1/members/{id}` requires it in `If-Match` (428 when missing; 412 `changed`, with nothing changed, for any other value) and returns the new `ETag`. A team manager's read carries no version. The UI tells the editor to edit again. No other route changes. |

## Addendum 12 — features before pilot feedback; My profile (2026-11-19)

Offered an upgrade path and resource measurements for #27, the owner answered **"No, keep them for later. Do some features"**, then selected **My profile, Trainings & events, Attendance and Membership fees** from a list that said #31 defers new features until pilot feedback and that the choice would be recorded as overriding it. Each feature's contract is approved separately before it is built.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 35 | Feature order | The four features are built before #28 pilot feedback, in the order My profile, Trainings & events, Attendance, Membership fees. This overrides the "after pilot feedback" rule of #31 for these four only; other post-MVP features stay deferred. #27 upgrade path and resource figures are postponed, not dropped. |
| 36 | My profile | Approved as OpenAPI **1.9.0**: `GET /v1/me/profile` returns the signed-in, active, verified member's own name, birth date, email, phone, gender, player flag, role and teams with the row version as `ETag` (not audited); `PUT /v1/me/profile/contacts` replaces only their own email and phone with `If-Match` (428/412 as in 1.8.0), audited as `SELF_CONTACTS_UPDATED`. Names, birth date, gender, player and role stay editor-only. No own export (offered, not chosen). |

## Addendum 13 — trainings and events (2026-11-19)

Shown the contract below, the owner answered **"Approve"** (with weekly repeats).

| # | Topic | Recorded decision |
| --- | --- | --- |
| 37 | Trainings and events | Approved as OpenAPI **1.10.0**. An event belongs to one team: kind (training, match, other), title, start, end, place, notes, cancelled. `MANAGE_TEAMS` for the team (club-wide or team-scoped) creates (optionally weekly until a date, at most 52, keeping the local time in the caller's time zone), replaces (including cancel), and deletes. `VIEW_TEAMS` or `MANAGE_TEAMS` reads the team's schedule; every member reads their own teams' and their current minors' events (`GET /v1/me/events`, eight weeks by default). No personal data, not audited. Deleting a team deletes its events. |

## Addendum 14 — attendance (2026-11-19)

Shown the contract, the owner chose **"Approve + counts"** and, for who sees the full list, **"Managers + viewers"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 38 | Attendance | Approved as OpenAPI **1.11.0**. A member answers YES, NO or MAYBE for themselves, a guardian for their current minor, for events of a team that member is in, until the event starts and not for a cancelled event; no free-text reason, so no health details are stored. Answers go with the event, the member, or the member's team assignment. Not audited. |
| 39 | Who sees answers | Holders of `VIEW_TEAMS` or `MANAGE_TEAMS` for the team see every answer. The agent shows them by public display name only (adults' full names, minors' first name and surname initial), because a `VIEW_TEAMS` holder may lack the directory grant needed for the roster. Everyone who sees an event also sees the totals (yes, no, maybe) without names. |

## Addendum 15 — membership fees (2026-11-19)

Shown the contract (tracking only, no payment processing), the owner chose **"New permission"** for who manages fees.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 40 | Membership fees | Approved as OpenAPI **1.12.0**. A holder of the new club-wide capability `MANAGE_FEES` (granted explicitly; editors do not get it; the first administrator gets it at bootstrap like the other capabilities) creates fee periods (name, due date, amount in CHF) that include every active member, changes one member's amount, excludes or re-includes members and marks payments by hand (date or open), with filters all, open, paid and excluded. Members see their own fees and guardians their current minors' (`GET /v1/me/fees`). Each change to a member's fee is audited (`FEE_ASSIGNED`, `FEE_CHANGED`, `FEE_PAID`, `FEE_UNPAID`, `FEE_REMOVED`); fees are part of the member export; erasure removes them. This completes the four features of addendum 12. |

## Addendum 16 — two more features (2026-11-19)

Asked what next after the four features, the owner chose **"More features"** over a security review, demo data and the #27 upgrade path, then picked **Excel import/export** and **Announcements** (not a calendar feed or email reminders). Each contract is approved separately, as before; this again overrides #31's "after pilot feedback" rule for these two only.

## Addendum 17 — member list import and export (2026-11-19)

Shown the contract, the owner chose **"Skip duplicates"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 41 | Member list as CSV | Approved as OpenAPI **1.13.0**, for `EDIT_MEMBERS`. Export: the active members as a CSV file Excel opens directly (semicolon, UTF-8 with BOM, header in the UI language, formula-like cells neutralized), every listed member audited as `MEMBER_LISTED`, with the cross-site protection of the member export. Import: a CSV saved from Excel (header in DE/EN/FR/IT, dates as 31.12.2010 or 2010-12-31, at most 1,000 rows), always previewed first; the commit creates only new valid members in one transaction, audited as `MEMBER_CREATED`, and never changes an existing member. |
| 42 | Duplicates and problems | A row matching an existing member (same first name, last name and birth date, ignoring case, archived members included) is skipped as "already exists". The agent also leaves rows with problems out (shown with the reason in the preview, counted before the confirmation), so one bad line does not block the others. |

## Addendum 18 — announcements (2026-11-19)

Shown the contract, the owner chose **"New permission"** for who posts club-wide.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 43 | Announcements | Approved as OpenAPI **1.14.0**. An announcement has a title (200), plain text (5,000, no formatting or images) and a show-until date (default 60 days, at most a year ahead), for the whole club or one team. Club-wide ones need the new club-wide capability `POST_ANNOUNCEMENTS` (granted explicitly; the first administrator gets it at bootstrap); team ones `MANAGE_TEAMS` for that team. Members see club-wide ones, their teams', their current minors' teams' and the teams they manage, newest first. The UI warns against private details in the text. Not audited (club content). The agent added the list of audiences a caller may post to, so the form offers only allowed choices. This completes addendum 16. |

## Addendum 19 — controller decisions for real member data (2026-11-20)

For issue #12 the owner decided **as the club's authorized representative** (the club is the controller), under Swiss
law only (nDSG/DSV; members live in Switzerland). Shown the [decision sheet](../privacy/controller-decisions.md), the
owner approved C1–C16 as written, with these answers: C5 **"Keep + right to object"**, C10 **"Approve as proposed"**,
C2 **"Approve"** and **"Approve all"** for the rest. The sheet is now DECIDED; the
[processing record](../privacy/processing-record.md) and the German [privacy notice draft](../privacy/datenschutzerklaerung-entwurf.md)
carry the decided values. Real member data still waits for the M2 gates and the Infomaniak data processing agreement.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 44 | Public member list (C5) | Kept as it is (adults' full names, minors' first name and surname initial, nothing else), with the right to object. Approved as OpenAPI **1.15.0** (V28, `members.listed_publicly`, default on): a member hides or shows their own name in *My profile* (`PUT /v1/me/profile/public-listing`) and an editor does it for any active member, for example a child at a guardian's request (`PUT /v1/members/{memberId}/public-listing`). Both need `If-Match` on the member's version and are audited as `PUBLIC_LISTING_CHANGED`. My profile, the editor's detail read and the member export carry `listedPublicly`. |
| 45 | Retention of new data (C10) | Deleted automatically by the hourly retention job: attendance answers 12 months after the event, events 24 months after the event, fee periods with their fees 24 months after the due date (the club's bookkeeping keeps its records for 10 years outside OpenClub, Art. 958f OR), announcements 6 months after their show-until date. Not audited (routine expiry of club records). |
| 46 | Basis and the rest (C1–C4, C6–C9, C11–C13, C15, C16) | As written in the sheet: membership-based processing without consent, data minimised, no sensitive data, Infomaniak as the only processor with an agreement, backups 30 days, requests within 30 days, breaches reported to the FDPIC where required, no impact assessment, a voluntary processing record. |

## Addendum 20 — volunteer shifts (2026-11-20)

Asked what next, the owner chose **all four** of volunteer shifts, a calendar feed, attendance statistics and email
notifications (in that order; each contract is approved separately, email additionally needs approval for any cost).
Shown the shifts contract, the owner chose **"Team members"** for who sees volunteers' names.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 47 | Volunteer shifts | Approved as OpenAPI **1.16.0** (V29). A shift belongs to one team event (title, start, end at most 24 hours and within a day of the event, 1–100 places, at most 50 per event) and is deleted with it. `MANAGE_TEAMS` for the event's team creates, changes and deletes shifts; a change may not leave fewer places than volunteers. Team viewers, managers, members and the guardians of its minor members see each shift with its volunteers' public display names. A member signs up themselves and a guardian their current minor, only for their team's shifts, until the shift starts (not for a cancelled event) and while places are free; the shift is locked, so two people never take the last place. Leaving the team ends the sign-ups. Not audited; sign-ups are in the member export. |
| 48 | Retention of sign-ups | The agent applied the decided attendance rule by analogy (C10): sign-ups are deleted 12 months after the shift, shifts with their event after 24 months. The owner can change this. |

## Addendum 21 — calendar feeds (2026-11-20)

Shown the calendar feed contract, the owner chose **"With notes"** (over leaving notes out, the recommendation, or
only team and time).

| # | Topic | Recorded decision |
| --- | --- | --- |
| 49 | Calendar feeds | Approved as OpenAPI **1.17.0** (V30). An active, verified member creates one private feed link in *My schedule*; it holds a 256-bit secret, is shown once and only its SHA-256 is stored. Creating a new link replaces the old one; the member can revoke it. Calendar apps load `GET /v1/calendar/{secret}` without a session: iCalendar with the events of My schedule (own and current minors' teams), four weeks back to six months ahead, with team, title, times, place, **notes** and cancelled status, never names, answers, counts or shifts. Every refusal is one 404. The link stops answering while the member has no verified identity and is deleted on archiving or when the login link changes. The web proxy does not log feed requests. Not audited. |
| 50 | What this means for privacy | Calendar services (for example Google or Apple) keep a copy of the feed, including notes, under the member's own account. The member chooses this; the privacy notice says so, and the UI warns not to share the link and to keep private details out of notes. |

## Addendum 22 — attendance statistics (2026-11-20)

Shown the contract, the owner chose **"Viewers + managers"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 51 | Attendance statistics | Approved as OpenAPI **1.18.0**. `GET /v1/teams/{teamId}/attendance-statistics` for holders of `VIEW_TEAMS` or `MANAGE_TEAMS` for the team (the people who already see each answer): for a range of UTC dates (default the last 12 months, at most 366 days), the number of the team's events held (started, not cancelled) and, per active team member by public display name, their Yes, No and Maybe answers and the events without an answer, plus totals. Uses stored answers only: no new data, not audited. Because answers are deleted after 12 months and assignments carry no dates, older events and events before a member joined count as unanswered; the UI says so. |

## Addendum 23 — email notifications (2026-11-20)

Asked for the mail sender, the owner chose **"Any SMTP, config only"** (no new cost; the synthetic demo stays
without email) and all four kinds of email. Shown the contract, the owner chose **"All on, can opt out"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 52 | Email notifications | Approved as OpenAPI **1.19.0** (V31). Four kinds: event changed or cancelled (a deletion counts as cancelled; editing an event that stays cancelled sends nothing), new events (a weekly series is one message), announcements, and a reminder within 24 hours before a volunteer shift. Every kind is on by default; each member turns kinds off and chooses the language (DE/EN/FR/IT) in *My profile* (`GET`/`PUT /v1/me/notifications`). Mail goes only to active members with a verified sign-in and an address; guardians get their current minors' team messages and reminders. Messages carry club, team and event or announcement details and a link, never other members' names (a guardian's reminder names their own child). Events that have ended are not reported. |
| 53 | Sending | Messages are queued in the same transaction as the change and sent once a minute from a database outbox (`SKIP LOCKED`), looking the address up at sending time; up to three tries; sent rows deleted after 7 days, unsent ones after 2 days. The SMTP server comes only from the environment written by the operator's config (`OPENCLUB_SMTP_*`, implicit TLS or STARTTLS, never plain text); without one nothing is queued. New dependency: Eclipse Angus Mail (free, EPL-2.0/GPL-2.0 with Classpath Exception), the Jakarta Mail implementation. |

## Addendum 24 — absences (2026-11-20)

Asked for more features, the owner chose **absences, carpooling and join requests** (match results not chosen).
Shown the absence contract, the owner chose **"Plain 'No'"** for what coaches see.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 54 | Absences | Approved as OpenAPI **1.20.0** (V32). In *My schedule* a member enters calendar days away (club time zone, `OPENCLUB_TIME_ZONE`, default Europe/Zurich) for themselves, a guardian for their current minor: from today, at most 366 days, ending within two years, at most 20 current or future absences per member; no reason is stored (an injury would be health data). Every event of the member's teams starting on such a day without an explicit answer counts as **No** in My schedule, answer totals, coaches' answer lists and statistics (view `effective_attendance`); coaches cannot tell it from a typed No; an explicit answer wins. Deleting the absence removes the implied No. Not audited; deleted 12 months after the last day; in the member export. |

## Addendum 25 — carpooling (2026-11-20)

Shown the carpooling contract, the owner chose **"Phone to passengers"**: a driver and the people they carry see each
other's phone number. This is a deliberate, narrow exception to "only explicitly granted roles may read member
contacts" (ADR-0007): it covers only the phone, only between a driver and their booked passengers (or the guardian
who booked for a child), and only while the booking exists.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 55 | Carpooling | Approved as OpenAPI **1.21.0** (V33). A signed-in adult in an event's team, or a guardian of a current minor in it, offers one ride per event (there, back or both; 1–8 seats; meeting point; departure from a day before the start to the end). Team members book a seat for themselves, guardians for their current minors, until departure (not for a cancelled event) and while seats are free; the ride is locked, so the last seat goes to one person. The driver changes the ride (not below the booked seats) or withdraws it; `MANAGE_TEAMS` for the team removes it. The team (members, guardians, viewers, managers) sees rides with display names. The driver sees each passenger's phone (for a minor, the booking guardian's); whoever booked sees the driver's; nobody else sees phones. Leaving the team ends one's bookings and offers; not audited; rides are deleted 12 months after the event; offers and seats are in the member export. |

## Addendum 26 — join requests (2026-11-20)

Shown the join request contract, the owner chose **"Adults + parents"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 56 | Join requests | Approved as OpenAPI **1.22.0** (V34). A public form, **off** until a holder of `EDIT_MEMBERS` switches it on; while on, the signed-out landing page shows *Join the club*. An adult applies for themselves; a parent applies for a child (the child's name and birth date, the parent's name, email and phone); the club's adult age decides which applies. Optional wished team (names listed only while the form is open) and a message of up to 500 characters; the privacy notice must be confirmed. Abuse limits without third parties: exact Origin, a hidden honeypot field (silently dropped), at most 5 submissions per client address and hour (in memory only, never stored), at most 200 open requests, the same person (address, first name, birth date) once while open (a repeat is answered like a success). No email to applicants. `EDIT_MEMBERS` lists requests (with contacts, to get in touch), accepts (creates the member with the usual checks and audit, a child without contacts, plus the wished team; the request is deleted) or declines (deletes). Open requests are deleted after 60 days. |
| 57 | Small additions while building | `GET /v1/public/join-requests` (is the form open, which teams) was added so the form can list teams; the "once while open" rule keys on address, first name and birth date, so a parent can apply for two children. |

## Addendum 27 — task-based navigation and the unfilled-shifts overview (2026-11-21/22)

Shown three layout mockups and a combined one, the owner chose the **combined layout** (2026-11-21): one page at a
time, *Start* with the missing answers first, *Schedule* with a per-child filter, *Family*, and *Team* or
*Administration* only for people who can use them. Asked whether to add an endpoint listing a member's own rights for
the menu, the owner chose **"No API change"**: the web app probes existing read-only endpoints instead. Asked how to
show helper shifts that still need people, the owner chose **"Add the endpoint"** (2026-11-22).

| # | Topic | Recorded decision |
| --- | --- | --- |
| 58 | Navigation | Web only, no contract change (PR #373). Menu entries come from existing read-only requests; only a denial hides one. |
| 59 | Unfilled shifts | Approved as OpenAPI **1.23.0**: `GET /v1/shifts/unfilled` for holders of `MANAGE_TEAMS` (club-wide or for a team) lists the shifts of the next 14 days with free places, of events that are not cancelled, only for the teams the caller manages, at most 100. Counts only, never who signed up. Shown on *Administration* → *Waiting for you* and on the *Team* page. |

## Addendum 28 — helpers-wanted email (2026-11-22)

Asked about an automatic reminder a week ahead, the owner chose **"Own switch"** for opting out.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 60 | Helpers wanted | Approved as OpenAPI **1.24.0** (V35). Seven days before a helper shift that still has free places (and more than a day ahead), the event's team and the guardians of its current minors get one email per event listing the shifts with their free places, once per shift. Not sent to people already helping at that event or whose child is, nor when the event is cancelled. Counts only, never names. Members turn it off with their own `helpersWanted` switch (on by default, like the other kinds); the field is optional in `PUT /v1/me/notifications`. Only when the installation sends email. |


**Note (2026-11-22):** addenda 21 and 24 name the page *My schedule*, which no longer exists since the task-based layout
(addendum 27): the calendar link and absences are now on *Family*. The decisions themselves are unchanged.

## Addendum 29 — answer reminders (2026-11-22)

Shown the answer-reminder design, the owner chose **"Approve as described"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 61 | Answer reminders | Approved as OpenAPI **1.25.0** (V36). (1) Automatically, once per event, when it starts in more than one and at most two days and is not cancelled: every active team member without an answer (an absence counts as one) gets an email, and for a current minor their guardians, naming that child. (2) A holder of `MANAGE_TEAMS` for the event's team can send the same reminder (`POST /v1/events/{eventId}/attendance-reminders`) until the event starts, at most once every 12 hours per event; the answer is the number of members without an answer, never names. (3) Members switch it off with their own `answerReminders` setting, on by default. Only when the installation sends email. |

## Addendum 30 — Swiss QR bills for fees (2026-11-22)

The owner approved Swiss QR bills for fees: "(1) Club settings get the club's payment details: IBAN (normal or
QR-IBAN), account holder name and postal address, editable with MANAGE_FEES; (2) each member (and parents for their
children) downloads a QR-Rechnung (PDF, A4 with the payment part) for an open fee from 'Familie → Beiträge'; amount,
due date and a reference built from period and member, so the treasurer can match payments in the bank statement
(marking as paid stays manual). The payer's address stays empty (we don't store addresses). Needs one new open-source
server library (qrbill-generator, MIT, free)."

| # | Topic | Recorded decision |
| --- | --- | --- |
| 62 | QR bills | Approved as OpenAPI **1.26.0** (V37). `GET`/`PUT /v1/club/payment-details` for `MANAGE_FEES`: a CH or LI IBAN (mod-97 checked; a QR-IBAN, institution ID 30000–31999, is detected), holder name (≤ 70), optional street (≤ 70) and building number (≤ 16, only with a street), postal code (≤ 16), town (≤ 35), country CH or LI; each change audited as `PAYMENT_DETAILS_CHANGED`. `GET /v1/me/fees/{periodId}/{memberId}/qr-bill` returns the A4 PDF for an open fee above zero of the caller or a current minor (the rule of `GET /v1/me/fees`): 403 for anyone else, 404 without a fee in that period, 409 when paid or without payment details. Reference from two stable numbers added by V37: a QR reference (27 digits, recursive mod-10) for a QR-IBAN, otherwise an ISO 11649 creditor reference `RFxx P<period>M<fee>`; shown per member in the treasurer's list. Message "<period> - <first name> <last name>" (≤ 140; a hyphen because the en dash is outside the Swiss payment character set). Generated on demand, never stored; no payer address. `GET /v1/me/fees` gains `qrBill` per fee. Library `net.codecrete.qrbill:qrbill-generator` 3.4.0 (MIT, free), which brings Apache PDFBox 3 (Apache-2.0) and Nayuki's QR code generator (MIT); its bundled Liberation Sans font is under the SIL Open Font License 1.1. |

## Addendum 31 — actual attendance and the J+S export (2026-11-25)

The owner chose seven new features on 2026-11-25 (actual attendance and J+S export, match squads, push
notifications, coach messages, field and hall booking, helper duty, bulk changes to events), asked for all of them
to be documented for every kind of user and separately for operators, and approved this design with **"Approve as
described"**. For the second design (match squads) the owner answered **"Approve all"**, which is recorded as
approval of the remaining designs as they were sent the same day; each is recorded in its own addendum when built.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 63 | Actual attendance and J+S | Approved as OpenAPI **1.27.0** (V38). (1) `GET`/`PUT /v1/events/{eventId}/presence` for `MANAGE_TEAMS` for the event's team: once the event has started (not cancelled) the coach ticks who came; people are the active team members (participants), the active members with a team-scoped `MANAGE_TEAMS` grant for the team (leaders) and anyone already recorded; at most 500 IDs, the event row locked while saving. (2) A record stays with its event, also after the member leaves the team, and is deleted with the event (24 months after it ends) or with an erased member; how long to keep it is recorded as open in #12. (3) An optional J+S person number per member (digits, at most 12), set by `EDIT_MEMBERS` holders with `If-Match` (`PUT /v1/members/{memberId}/js-person-number`, audited `JS_NUMBER_CHANGED`), shown to editors, to the member in My profile and in the member export. (4) `GET /v1/teams/{teamId}/js-attendance` checks a date range (rows, people without a number, trainings without a place); `GET …/js-attendance.csv` returns the file for the NDS attendance import (columns `PERSONENNUMMER;FUNKTION;DATUM;AKTIVITAETSTYP;ZEIT;DAUER;ORT`, Swiss time, trainings as *Training*, matches as *Wettkampf*, other events left out), audited `JS_ATTENDANCE_EXPORTED`. The separator (semicolon) and the column details follow the BASPO import description; an import into a real NDS course is not yet tested. |

## Addendum 32 — match squads (2026-11-25)

Shown the squad design, the owner answered **"Approve all"** (see addendum 31).

| # | Topic | Recorded decision |
| --- | --- | --- |
| 64 | Match squads | Approved as OpenAPI **1.28.0** (V39). `GET`/`PUT /v1/events/{eventId}/squad` for `MANAGE_TEAMS` for the team, for matches only (409 `not_match` otherwise) and until the match starts (409 `closed`): the called members (active team members only, at most 500), an optional meeting time and meeting point (≤ 200). Each member called for the first time gets a `SQUAD_CALLED` email naming the match, the meeting time and place, and so do the guardians of a called minor; a new `squadCalls` switch (on by default, optional in PUT) turns it off. Removed members are not emailed. Members and guardians see only their own status in `GET /v1/me/events` (`squad`: called or not, meeting details only when called) on the event and schedule pages; the list only the team's managers. The squad goes with its event; a member leaves it with the team (trigger). Not audited. |

## Addendum 33 — changing several events at once (2026-11-26)

Approved with the selection of 2026-11-25 ("Approve all", see addendum 31).

| # | Topic | Recorded decision |
| --- | --- | --- |
| 65 | Bulk changes | Approved as OpenAPI **1.29.0** (no migration). `POST /v1/events/bulk`: for 1 to 50 teams, each needing `MANAGE_TEAMS`, a range of Swiss dates (≤ 366 days) and the chosen kinds, cancel, reinstate or set a new place (≤ 200) on every event that has not started and would change; at most 500 events; all or nothing, the grant checked again per row in the update. `preview` counts without changing. One summary email per member and guardian of the affected teams (their `eventChanges` switch). The app offers it per team on the team's schedule; the field or hall of feature 6 is added there when venues exist. |

## Addendum 34 — fields and halls (2026-11-26)

Approved with the selection of 2026-11-25 ("Approve all", see addendum 31).

| # | Topic | Recorded decision |
| --- | --- | --- |
| 66 | Venues | Approved as OpenAPI **1.30.0** (V40). A new club-wide capability `MANAGE_VENUES`, given to the first administrator at bootstrap (others only by an explicit grant), adds, renames (unique, ignoring case, ≤ 100) and deletes the club's fields and halls (`POST /v1/venues`, `PUT`/`DELETE /v1/venues/{venueId}`); deleting keeps the events without the venue. Every active member reads the list (`GET /v1/venues`, with `canManage`) and each venue's bookings for up to 62 days (`GET /v1/venues/{venueId}/bookings`: team, kind, title, times; never people). Events take an optional `venueId`; two events that are not cancelled never hold one venue at overlapping times (exclusion constraint with `btree_gist`; the API answers 409 `venue_taken` naming the other event's team, title and start, and checks every weekly copy). Bulk changes gain `SET_VENUE`. Not audited (club content). |

## Addendum 35 — coach messages (2026-11-26)

Approved with the selection of 2026-11-25 ("Approve all", see addendum 31).

| # | Topic | Recorded decision |
| --- | --- | --- |
| 67 | Coach messages | Approved as OpenAPI **1.31.0** (V41). `POST /v1/events/{eventId}/messages` for `MANAGE_TEAMS` for the event's team: audience everyone in the team, those who said yes, those without an answer, or the squad of a match; subject ≤ 150 (one line), text ≤ 5000. The server emails each of them and the guardians of their minors (`TEAM_MESSAGE`, with the sender's name, never addresses), honouring the new `teamMessages` switch (on by default). At most 10 per team within 24 hours (the team row is locked while counting); allowed until a day after the event. Only time, audience and number of members are logged (`coach_messages`, with the event), never the text beyond the outbox. |

## Addendum 36 — helper duty (2026-11-26)

Approved with the selection of 2026-11-25 ("Approve all", see addendum 31).

| # | Topic | Recorded decision |
| --- | --- | --- |
| 68 | Helper duty | Approved as OpenAPI **1.32.0** (V42). Club settings `helper_quota` (0–50, 0 = off, default 0) and `season_start_month` (1–12, default August), set by `MANAGE_FEES` holders (`PUT /v1/club/helper-duty`). Liable: active adults in a team and guardians of an active minor in a team. A shift counts once it has started and its event was not cancelled, for the volunteer and for the guardians of a current minor volunteer (two guardians of one child both get it). Each member sees their own count (`GET /v1/me/helper-duty`); the treasurer sees every liable adult's full name with done and owed (`GET /v1/helper-duty`). Any substitute fee stays a normal manual fee. Counts derive from sign-ups, which end when a member leaves the team and are deleted 12 months after the shift. Not audited. |

## Addendum 37 — push notifications (2026-11-26)

Approved with the selection of 2026-11-25 ("Approve all", see addendum 31).

| # | Topic | Recorded decision |
| --- | --- | --- |
| 69 | Push notifications | Approved as OpenAPI **1.33.0** (V43), Web Push with VAPID and **no payload**: the server sends an empty push to the member's registered browsers for every queued message, and the service worker reads the subjects from `GET /v1/me/push-inbox` with the member's own session, so no personal data passes through the vendors' push services. Implemented with the JDK only (ES256 signing, `java.net.http`), no new dependency. Operator settings from the environment that the deployment YAML writes: key pair, a `mailto:` contact and the **allowed push hosts** (`push.hosts` in the demo YAML), outside of which no endpoint is accepted or called. Members keep at most 5 devices; an endpoint moves to whoever registered it last; endpoints the service reports gone (404/410) are deleted. The same per-kind switches as for email apply; messages are now queued for members with an email address **or** a device, so push works without SMTP. Not audited. |

