# R03 privacy, retention and lifecycle — proposal for owner decision (2026-11-18)

**Status: the owner approved every proposed default on 2026-11-18 ([ADR-0008 addendum](../decisions/0008-owner-decisions-2026-11-18.md), decision 7), except the two rows that have no default (jurisdiction and lawful basis), which are for the controller. The controller's approval is still required, none of this is legal advice, and none of it authorizes real member data.** The owner asked for a concrete draft ([ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md), decision 1) for [#12](https://github.com/dkoller/OpenClub/issues/12). It fills the rows still marked "Open" in the [privacy inventory](privacy-inventory.md) with **proposed defaults**. The accountable club representative (the controller) decides jurisdiction, lawful basis, notices and every duration; the owner decides the product defaults. Where the owner has already decided, the decision is cited ([ADR-0007](../decisions/0007-owner-decisions-2026-11-17.md)).

How to use it: for each row, approve the proposed default or write a different value in #12. The agent then updates this file, the inventory and the contract notes, and only after that implements anything (#26).

## 1. What is stored (unchanged, from the inventory)

`clubs`, `accounts`, `account_identities`, `members` (names, date of birth, email, phone, gender, player flag, role, archive timestamp), `teams`, team assignments, club and team capability grants, `auth_sessions`, `oidc_login_transactions`. Session handles are stored only as hashes. Today **no HTTP operation returns email, phone, date of birth or gender**: the frozen contract returns names only, and those fields can only be written when creating a member.

## 2. Proposed field access (fills the inventory's "Open" rows)

Capabilities that exist today: `VIEW_MEMBER_DIRECTORY`, `EDIT_MEMBERS`, `MANAGE_PERMISSIONS` (club-wide), `VIEW_TEAMS`, `MANAGE_TEAMS` (club-wide or one team). Owner decision, already made: **only explicitly granted roles read email and phone**: holders of a member-editing permission, and team managers for their own team only; ordinary members see names only (ADR-0007, decision 4).

| Field | Proposed readers | Proposed rule |
| --- | --- | --- |
| Public display name | Anyone, no login | As approved for synthetic M1: adults in full, minors first name plus surname initial. Real publication needs controller approval and a notice. |
| Full name, member ID | `VIEW_MEMBER_DIRECTORY` | Unchanged. |
| Email and phone, adults and minors | `EDIT_MEMBERS` (club-wide); `MANAGE_TEAMS` for **members of that team only** | Per ADR-0007. A separate "view contacts" capability is **not** proposed: it would add a permission without adding a boundary. Every contact read by a team manager is an audit event (section 5). |
| Date of birth | `EDIT_MEMBERS` only | Team managers and directory viewers see only "adult" or "minor", computed on the server from the club's adult age. No birthday in lists. |
| Gender, player flag | `EDIT_MEMBERS`; `MANAGE_TEAMS` for the player flag of own-team members | Gender only where the club policy requires it. No fixed list of options. |
| Account issuer and subject | Nobody over HTTP | Technical operator only; never in lists, exports or logs. |
| Anything for an archived member | `EDIT_MEMBERS` only | Archived members are hidden from lists, teams and the public directory. Today they also answer 404 to editors; the proposal adds editor-only access to an archived record solely for export, reactivation and erasure. |

Not proposed and requiring a separate decision: guardian relationships, photographs, health data, any contact disclosure to all members.

## 3. Proposed retention

**Implemented (V13):** the audit-event 12-month and orphan-account 2-year rules run as hourly bounded batches ([retention cleanup](../security/retention-cleanup.md)). Archived-member erasure, backups and logs are not part of that job.

All durations are **proposals**. Defaults reflect what the code does today or the smallest period that keeps the club workable.

| Data | Proposed default | Basis / note |
| --- | --- | --- |
| Browser sessions | 30 minutes idle, 12 hours absolute (**already implemented**); expired rows deleted by the cleanup job | No change. |
| Login transactions | 5 minutes (**already implemented**) | No change. |
| Active member record | Kept while the person is an active member | Purpose: club administration. |
| Archived member record | **24 months** after archiving, then erased (section 4), unless the controller records a legal hold | The club may need membership history for disputes and fees. The controller picks the real period; 24 months is a placeholder. |
| Erase on request | Within **30 days** of a verified request, unless a statutory duty to keep the data applies | The controller names the exceptions and the responsible person. |
| Account without a linked member | Deleted after **2 years** without a login (proposed 90 days; **changed by the owner on 2026-11-18**) | Reduces orphaned identifiers. |
| Database backups | Rolling **30 days**; erasure reaches backups by expiry, never by editing a backup | Operator decision; encrypted, off-site storage is an M2 operator task (#27). |
| Audit events (section 5) | **12 months** | Contains no names, birth dates or contacts. |
| Application logs | No personal data in logs (already a guardrail); 14 days | Operator decision. |

## 4. Proposed erasure and export

**Erase = hard delete, not anonymize**, for a member who is archived. Archiving already clears team assignments and grants, and only unlinked ordinary members can be archived (V10/V11), so an archived member row has no dependents and can be deleted completely, which is simpler and easier to prove than field-level anonymization. A minimal audit event records that the erasure happened. Members who are active, linked to an account, or administrators cannot be erased directly: they are unlinked and archived first, which keeps the last-administrator and recovery protections intact. Erasure is a **two-step** action by an administrator holding `EDIT_MEMBERS`: archive, then erase with a typed confirmation (as team deletion does today).

**Export** (right of access or portability): JSON for one member containing their stored fields, team assignments and grants, produced by an authorized administrator after verifying the requester's identity. Account issuer and subject, session data and other people's data are excluded. First version is an operator command; an HTTP operation follows only if wanted.

**Reactivation** (the approved M2 draft's `POST /v1/members/{memberId}/reactivate`) restores an archived member **without** restoring grants, roles or the account link. It is refused once the member has been erased.

## 5. Proposed audit events

**Implemented so far (V12, V14): member created, updated and archived; role changes; grant added and revoked; sole-administrator recovery; first-club bootstrap. Operators read the trail with the read-only `showAuditTrail` command, and events older than 12 months are deleted.** The remaining events arrive with their operations. Recorded in the proposal: member created, updated, archived, reactivated, erased and exported; role changes; grant changes; each contact read by a team manager; invitations created, accepted and revoked (#20); sole-administrator recovery. Each event holds the event type, the acting account ID, the target member UUID, a timestamp and no names, birth dates or contacts. Events are written in the same transaction as the change. Operators read them with a command; there is no audit HTTP API in this proposal.

## 6. What the approved M2 draft does not cover

The draft approved in ADR-0008 contains only member replacement and reactivation. Implementing this proposal also needs **new operations that are in no contract yet**: reading a member's contact and birth-date fields, archive-then-erase, export, and an audit read if wanted. They require a draft extension and the owner's approval before any code, and they remain M2 (real data), not part of the frozen M1 surface.

## 7. Decisions needed

| # | Decision | Proposed default | Who decides |
| --- | --- | --- | --- |
| 1 | Jurisdiction, controller, processors, hosting location | Not proposed: facts, not defaults | Controller and operator |
| 2 | Lawful basis and notices for each field | Not proposed: needs counsel | Controller |
| 3 | Contact visibility | Section 2: editors, plus team managers for their own team | Owner, then controller |
| 4 | Minors' contacts | Same readers as adults, with audit; no guardian relationships | Controller |
| 5 | Archived retention | 24 months, then erase | Controller |
| 6 | Erase mode | Hard delete after archive | Owner |
| 7 | Request deadline | 30 days | Controller |
| 8 | Backup retention | 30 days rolling | Operator and controller |
| 9 | Audit retention | 12 months, no personal data in events | Owner |
| 10 | Orphan accounts | Delete after 90 days without login; **owner changed it to 2 years** (ADR-0008, decision 14) | Owner |

## 8. If approved: implementation order (not started)

1. Extend the M2 draft contract with the new operations; owner approves the version.
2. Audit event table and writer, in the same transaction as each change (also needed by #20).
3. Archive-then-erase operation and export command, with synthetic PostgreSQL tests including backup-restore behavior and the last-administrator rules.
4. Contact and birth-date reads with the access rules in section 2, with an authorize-before-validate and revocation matrix like the M1 routes.
5. Retention cleanup jobs.
6. Four-language administration UI and browser tests.

Each step is its own pull request under the usual size limit, and none touches real data.
