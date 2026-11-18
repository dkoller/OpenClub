# R03 — Single-club privacy inventory and approval gates

**Status:** (A concrete proposal for the open rows below is in [privacy proposal 2026-11-18](privacy-proposal-2026-11-18.md); it is not approved.) Working privacy assessment, **not legal approval or permission to use real data**. Tracked by [#12](https://github.com/dkoller/OpenClub/issues/12). The applicable jurisdiction and responsible club operator must be identified before any pilot processes real personal data.

OpenClub deploys **one club per independent installation and PostgreSQL database**. It has no global person directory, no cross-installation login or data sharing, and no tenant selector. The old `persons`, `person_identities` and `memberships` model was removed in pre-production PR #79. Real member records have never been approved for import or use.

## Actual V1 PostgreSQL data inventory

| Table | Data stored | Proposed purpose | Exposure / lifecycle boundary |
| --- | --- | --- | --- |
| `clubs` | Club UUID, name, slug, adult threshold and gender-required policy, timestamps | One installation's configuration | Only authorized users; name may identify a small organization/person |
| `accounts` | Local account UUID, placeholder display label, creation timestamp | Technical OIDC principal | Not a member directory; deletion requires session and identity cleanup |
| `account_identities` | OIDC issuer, case-sensitive subject, local account link, timestamps | Bind a **verified** identity, not match by email | High-value identifiers; never in member summaries or ordinary logs |
| `members` | Local member UUID, optional account link, names, DOB, email, phone, gender, player flag, interim ADMIN/MEMBER role, creation timestamp | Member administration, age/player rule checks | Full fields are private; basic name directory itself needs approval; export/correction/retention unresolved |
| `auth_sessions` | Session-handle hash, account link, CSRF token, created/used/expiry times | Authenticated browser sessions | No plaintext handle in database; short retention and expiry cleanup |
| `oidc_login_transactions` | Hashed state/browser binding, nonce, PKCE verifier, expiry | Short-lived login completion | Security-only; expiry cleanup, no public exposure |

Member birth date is **mandatory for all**; email **and** phone are mandatory for adults under a club-configured threshold (currently default 18); player gender is mandatory only when explicitly enabled by club policy. These are **accepted product requirements**, not a conclusion about data-protection necessity or lawful basis. The database validates writes; birthdays or policy changes require further lifecycle handling.

## Owner-approved synthetic M1 public-name rule (2026-11-16)

The owner decided that **active members' names are publicly viewable without login**. Adults' names display in full (example: `Peter Smith`); minors' names display **first name plus the initial of the surname and a period** (`Peter S.`). Minor status is evaluated on the server from the stored date of birth and the installation's configured adult-age threshold at the time of each response; a birthday or threshold change must immediately change the public display. Never send the full minor surname to an anonymous client, including hidden JSON properties, HTML, search responses or logs. Public responses must expose only a preformatted `displayName`, not separate first/last names, date of birth, exact age, contact information, member/account UUIDs, gender or grants. Exclude archived/inactive members.

**M1 scope:** This is a technical/product disclosure decision for fictitious synthetic fixtures only, **not legal clearance to publish real adults' names or identifiable minors' information**. A first name and surname initial can still identify a child. The operator/controller must separately approve actual publication, notices, purpose, retention, minors' safeguards and applicable law before M2 real-data use. The authenticated administration API remains distinct: full legal names and private fields require independently reviewed permissions and must never be inferred from this public endpoint. To avoid bypassing masking, public search/filtering/cursors must not reveal full minor surnames through matches, ordering, tokens or record identifiers. Test both unauthenticated full-adult and masked-minor output and denied private-field access.

## Remaining least-disclosure decisions — required before real-data pilot

The public **display-name-only** read is now implemented under `/api/v1/public/members` with server-side age masking, archived-member exclusion and a four-language browser view; synthetic PostgreSQL/HTTP tests verify output minimization. It is separate from authenticated member administration and is **not** real-data publication approval. Other member-list/detail responses containing identifiers or private fields remain restricted pending authorization and field-level review. The internal query introduced in merged [PR #86](https://github.com/dkoller/OpenClub/pull/86) selects full name and local UUID and **must not be reused unfiltered as a public response**.

| Data / action | Suggested initial boundary for review | Decision status |
| --- | --- | --- |
| Public display name of active adults/minors | **Owner-approved for synthetic M1:** adults `Peter Smith`, minors `Peter S.`; anonymous, preformatted display only, no member UUID; full real-data publication needs controller approval | **M1 technical decision recorded; M2 approval open** |
| Full member names and local member ID | Authenticated authorized administration only; no full minor surname in a public API | **Private-field access review open** |
| Full DOB, including minors | Restricted administration for a documented age/eligibility purpose; avoid birthday disclosure | **Open** |
| Email and phone, adults | Explicitly approved contact-related capability; never assume all club members can view | **Open** |
| Email and phone, minors | Deny general directory access by default; resolve verified guardian/coach/administration needs | **Open** |
| Gender and player status | Restrict to approved sports/eligibility workflows; do not assume binary options | **Open** |
| Authentication issuer/subject and session secrets | System/authorized technical operators only; never member-list or export defaults | **Technical restriction; operator review open** |
| Member creation/update/archive | Explicitly delegated capability with audited authorization and accountless record support | **Open (#76/#18)** |
| Export, erasure, correction | Verified request, scope-limited operator action with documented statutory exceptions | **Open (#26)** |

Do not introduce guardian relationships, photographs, health information or membership-wide contact disclosure without separate requirement and privacy review. Installation isolation is not a substitute for least-privilege authorization inside a club.

## Controller decisions required before first real-data pilot

The accountable club representative and, where needed, qualified privacy counsel must record **actual decisions**, reviewer and date in an operator-controlled private register (not committed personal details). Never assume Swiss law applies solely because a developer is in Switzerland.

| Gate | What must be decided and evidenced | Responsible sign-off |
| --- | --- | --- |
| Jurisdiction and roles | Where members and hosting are located, controller, operators/processors and cross-border recipient arrangements | Club/controller + operator |
| Purpose and lawful basis | Necessity of names, exact DOB, both adult contacts, conditional gender, and what notice/consent (if any) applies | Club/controller + privacy reviewer |
| Minor safeguards | Who can view child/guardian contacts, youth eligibility, parental/guardian verification and when access expires | Club/controller + privacy reviewer |
| Field-level matrix | Directory visibility by capabilities and club/team scope; deny default for unapproved fields | Club/controller + security reviewer |
| Age rule | Exact local adult threshold, birthday transition checks, effects of club policy changes and how missing historical data is handled | Club/controller |
| Retention triggers | Active/archived/departed member lifecycle, category-specific durations, statutory holds and secure deletion | Club/controller |
| Subject rights | Identity verification, correction, export, deletion/anonymization workflow, response responsibility and exceptions | Club/controller |
| Infrastructure | Hosting location, backups/encryption/restore, backup expiry/erasure, secrets, incident response and processor terms | Operator + club/controller |
| Access evidence | Audit events without PII payloads, unauthorized access tests and revocation behavior | Security reviewer |

**No durations, consent mechanism, guardian entitlement, or legal basis have been approved.** The table is an approval checklist, not a substitute for policy or law.

## Implementation guardrails and remaining work

- Keep all sample identities fictitious; prohibit real member data in git, CI, screenshots, logs and demo fixtures.
- Require reviewed R04 OpenAPI contract before public **display-name-only** reads; never expose unmasked member directory data through public routes. Private/admin member endpoints still require #76 explicit permissions, verified session, Origin/CSRF for writes, and nondisclosing 401/403/404 behavior. Public display endpoint may return 200 to anonymous callers, but must never return IDs or private fields.
- Do not auto-merge an OIDC account with a member using matching email/name. Invitation-based linking requires independent verification (#20).
- Record role/grant changes and exports in minimal audit events; do not print DOB, contact data, gender or tokens in application logs.
- Test archive, export, erasure, backup restoration and policy transitions on synthetic data before real use (#26/#27).
- Document an actionable incident owner, privacy-notice delivery and operator support before any pilot (#27/#28).

## Reference only

For Switzerland, the [FDPIC guidance for clubs and associations](https://www.edoeb.admin.ch/en/data-protection-in-clubs-and-associations) provides a useful starting point on purpose limitation, transparency and data minimization. It is not a determination of the project's governing law or a general permission to process children's data. See [ADR-0004](../decisions/0004-member-policy.md), [single-club ADR-0005](../decisions/0005-single-club-installation.md), and the [frozen synthetic M1 contract](../contracts/openapi-v1.yaml) and the [unapproved M2 member proposals](../contracts/openapi-m2-draft.yaml).
