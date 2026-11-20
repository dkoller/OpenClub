# Processing record (Verzeichnis der Bearbeitungstätigkeiten) — DRAFT

**Status: DRAFT, 2026-11-19, for the controller's decision ([sign-off sheet](controller-decisions.md)).** Kept
voluntarily; small clubs with low-risk processing are exempt from the duty (Art. 12 nDSG, Art. 24 DSV). Not legal
advice. Placeholders are in square brackets.

| Field | Content |
| --- | --- |
| Controller | [Club name], [address]; represented by [name of the owner], [email] |
| Contact for data protection requests | [name], [email] |
| Processors | Infomaniak Network SA, Switzerland: server hosting (VPS) and, if configured, email sending. Data processing agreement: [date concluded] |
| Disclosure abroad | None intended (hosting in Switzerland; verify the data-centre location in the Infomaniak contract) |
| Software | OpenClub (open source, self-hosted), with Keycloak or Rauthy on the same server for sign-in |

## Activities

| # | Activity | People | Data | Purpose | Who has access | Retention |
| --- | --- | --- | --- | --- | --- | --- |
| A1 | Member register | Members, minors | First and last name, date of birth, email, phone, gender (if the club requires it for players), player flag, role, archive date | Membership administration under the statutes | Editors; the member (own record); guardians (own minors: birth date, player, teams, contacts); team managers (own team's active members: name, contacts, player; each read audited) | While a member; 24 months after archiving, then erased |
| A2 | Public member list | Members | Adults' full names; minors' first name and surname initial | Showing who belongs to the club | Anyone | While a member and not excluded (C5) |
| A3 | Teams and permissions | Members | Team assignments, club or team permissions | Running teams; least-privilege access | Editors, team viewers and managers, permission managers | While assigned or granted |
| A4 | Sign-in | Members, guardians | Sign-in identifier at the club's own identity provider (issuer and subject), sessions, one-time invitation (stored only as a hash) | Letting members and guardians sign in | Operator only | Sessions 12 hours; invitations 7 days; accounts without a member deleted after 2 years without sign-in |
| A5 | Guardianships | Minors, guardians | Which adult member is guardian of which minor, since when | Guardians manage their child's contacts and answers | Editors; the guardian | Ends at the club's adult age, on archive or unlink |
| A6 | Trainings and events | Teams | Kind, title, time, place, notes, cancelled (no personal data intended) | Planning | Team members, their guardians, team viewers and managers | 24 months after the event (proposed) |
| A7 | Attendance | Members, minors (via guardians) | Yes, no or maybe per event; no reasons | Planning who comes | The member or guardian; the team's viewers and managers (by public display name); everyone sees only totals | 12 months after the event (decided, deleted automatically); immediately when leaving the team |
| A7b | Volunteer shifts | Members, minors (via guardians) | Which shift of a team event someone signed up for, and when | Organising helpers | The team (managers, viewers, members and guardians) by public display name | 12 months after the shift (deleted automatically, by analogy with attendance, ADR-0008 addendum 20); immediately when leaving the team; with the event after 24 months |
| A7c | Calendar feed | Members (their own link) | A hashed secret and its creation time; the feed shows the events of My schedule with notes, no names | Seeing the schedule in one's own calendar app | Only the holder of the link; the member's calendar service keeps a copy under the member's own account | Until replaced or revoked; deleted on archiving or when the login link changes |
| A8 | Membership fees | Members | Amount, due date, paid date (no bank data) | Tracking who paid (no payment processing) | Treasurer (`MANAGE_FEES`); the member; guardians for their minors | 24 months after the due date (proposed); the club's own bookkeeping keeps its records for 10 years outside OpenClub |
| A9 | Announcements | — (club content) | Title, text, show-until date | Informing members | Members of the audience | 6 months after show-until (proposed) |
| A10 | Member list exchange | Members | A1 data in a CSV file for Excel | Moving from the club's Excel list | Editors; every export audited per member | The file is the editor's responsibility once downloaded |
| A11 | Audit trail | Members, actors | Identifiers only: who (account ID), what (event type), on whom (member ID), when | Accountability and abuse detection | Operator (command line) | 12 months |
| A12 | Requests | Members, guardians | Export (JSON) and erasure on request | Rights of access, portability and deletion (Art. 25, 28, 32 nDSG) | Editors, operator | Answered within 30 days |

## Technical and organisational measures (Art. 8 nDSG)

- TLS for every connection; sign-in through the club's own OpenID Connect provider; server-side sessions (12 hours,
  idle 30 minutes); CSRF and origin checks on every change.
- Least privilege: separate permissions for members, teams, permissions, fees and announcements; every check runs in the
  same database statement as the read or change.
- Identifier-only audit trail; no personal data in application logs.
- Minimisation: no health data, no reasons for absences, no bank data, public list without contacts or birth dates.
- Data export and erasure built in; erasure deletes the record (backups expire, never edited).
- Independent security reviews of each feature set (2026-11-19); automated tests for every permission boundary.
- Backups: encrypted, off-site, 30 days rolling (production, M2; not for the synthetic demo).
