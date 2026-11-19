# Controller decisions for real member data (#12) — sign-off sheet

**Status: DRAFT for the controller's decision, 2026-11-19.** Not legal advice. Nothing here authorizes real member
data until every row below is decided and the M2 gates (#180 recovery drill, production backups) are met.

- **Controller:** the pilot club (Verein). The owner decides for the club as its authorized representative (owner
  answer of 2026-11-19, "I decide for the club").
- **Applicable law:** Swiss Federal Act on Data Protection (nDSG/revDSG) and its ordinance (DSV) only; members live in
  Switzerland (owner answer of 2026-11-19). If members abroad join, GDPR applicability must be re-checked.
- **Documents this sheet approves:** the [processing record](processing-record.md) and the
  [privacy notice draft](datenschutzerklaerung-entwurf.md) (German, for members and guardians).

Each row has a proposed answer. Mark it **approved**, or write the changed answer; the agent records the result as an
addendum to [ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md) and implements what needs code.

| # | Decision | Proposed answer | Reasoning |
| --- | --- | --- | --- |
| C1 | Purposes | Club administration: membership, teams, trainings and events, attendance, membership fees, communication with members and guardians, sign-in | Exactly what the app does; nothing else (no marketing, no profiling). |
| C2 | Basis for processing | Membership relationship under the club's statutes; for minors, through their guardians. No consent asked for the core purposes. The public member list (C5) relies on information plus the right to object. | Under nDSG, private processing needs no legal basis as long as the principles (Art. 6) are kept; a justification (Art. 31) is needed only where processing would otherwise violate personality rights. Consent would be revocable and is not a good fit for data the club needs to run. |
| C3 | Data minimised | Required: first and last name, date of birth; adults also email and phone (club rule); gender only when the club requires it for players. Everything else optional. No health data, no free-text reasons for absences. | Date of birth is needed for the adult/minor rules and age categories. |
| C4 | Sensitive data | None intended. Gender is optional free text and only required for players if the club turns the rule on; announcements and event notes carry a warning against health or private details. | Keeps the processing low-risk (no Art. 5 lit. c data). |
| C5 | Public member list | Keep: anyone can see adults' full names and minors' first name plus surname initial, nothing else. Any member may object; the club then removes them from the list (needs a small change, see C14). | Common for clubs; must be in the notice. Alternative: make the list visible only after sign-in. |
| C6 | Who sees contacts and birth dates | Editors (`EDIT_MEMBERS`), team managers for their own team's active members (each read audited), guardians for their own minors, every member for themselves | Already built (ADR-0007, ADR-0008). |
| C7 | Minors | Minors cannot sign in; up to two guardians recorded by the club after checking the relationship outside the app; guardianship ends at the club's adult age (18) | Already built (guardian design, ADR-0008 addendum 8). |
| C8 | Processors | Infomaniak (hosting, and email if used), Switzerland. A data processing agreement with Infomaniak is concluded before real data. No other processors; Keycloak or Rauthy run on the club's own server. | Art. 9 nDSG. No disclosure abroad (Art. 16) as long as hosting stays in Switzerland; verify the data-centre location in the contract. |
| C9 | Retention (already approved defaults, ADR-0008) | Archived member record 24 months, then erased; audit trail 12 months; accounts without a member 2 years; sessions 12 hours | Implemented (V13, V15). |
| C10 | Retention (new data) | Attendance answers: deleted **12 months after the event**. Fee records: deleted **24 months after the period's due date**; the club's bookkeeping keeps its own records for 10 years (Art. 958f OR) by exporting what it needs, so OpenClub is not the accounting book. Announcements: deleted **6 months after show-until**. Event details: deleted **24 months after the event**. | Short enough to minimise, long enough for a season and disputes. |
| C11 | Backups | Production: encrypted, off-site, rolling **30 days**; an erasure reaches backups by expiry. The synthetic demo has none (ADR-0008 addendum 6). | Operator task for M2 (#27). |
| C12 | Requests (access, correction, deletion, portability) | Answered within **30 days** (Art. 25 nDSG) by the owner as contact person; identity checked before export; export via the app (JSON) | Export and erasure exist (OpenAPI 1.3.0, 1.7.0). |
| C13 | Breaches | The owner is the incident contact; a breach likely to cause a high risk is reported to the FDPIC (EDÖB) as soon as possible and affected people are informed where needed (Art. 24 nDSG); every incident is noted in a private register | No automatic reporting in the app. |
| C14 | Code changes needed for this sheet | (a) automatic deletion for C10; (b) an "excluded from the public list" flag per member (C5) | Small; each with its own contract approval. |
| C15 | Impact assessment (Art. 22) | Not required: no sensitive data, no profiling, a small number of people, no new technology risk. Re-check if health data or photos are ever added. | Recorded so the decision is visible. |
| C16 | Processing record | Kept voluntarily ([processing record](processing-record.md)); not required for a club under 250 employees with low-risk processing (Art. 12 nDSG, Art. 24 DSV) | Good practice and the basis of the notice. |
