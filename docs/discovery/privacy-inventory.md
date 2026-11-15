# R03 — Single-club privacy inventory and approval gates

**Status:** Working privacy assessment, **not legal approval or permission to use real data**. Tracked by [#12](https://github.com/dkoller/OpenClub/issues/12). The applicable jurisdiction and responsible club operator must be identified before any pilot processes real personal data.

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

## Proposed least-disclosure baseline — requires club/privacy approval

Until the data controller explicitly approves a field/role matrix, the application must **not expose member-list or member-detail HTTP APIs**. The internal query introduced in merged [PR #86](https://github.com/dkoller/OpenClub/pull/86) selects only name and local UUID and is not registered as an HTTP route. Authentication and interim ADMIN/MEMBER role alone do not justify broad visibility.

| Data / action | Suggested initial boundary for review | Decision status |
| --- | --- | --- |
| Name and local member ID | Authorized members with explicit directory-read grant; visibility of children still assessed | **Open** |
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
- Require explicit reviewed R04 OpenAPI contract and #76 permission model before exposing any member HTTP response. Restrict list DTOs to minimum approved fields; 401 unauthenticated, 403 insufficient permission, and nondisclosing 404 for non-visible individual members.
- Do not auto-merge an OIDC account with a member using matching email/name. Invitation-based linking requires independent verification (#20).
- Record role/grant changes and exports in minimal audit events; do not print DOB, contact data, gender or tokens in application logs.
- Test archive, export, erasure, backup restoration and policy transitions on synthetic data before real use (#26/#27).
- Document an actionable incident owner, privacy-notice delivery and operator support before any pilot (#27/#28).

## Reference only

For Switzerland, the [FDPIC guidance for clubs and associations](https://www.edoeb.admin.ch/en/data-protection-in-clubs-and-associations) provides a useful starting point on purpose limitation, transparency and data minimization. It is not a determination of the project's governing law or a general permission to process children's data. See [ADR-0004](../decisions/0004-member-policy.md), [single-club ADR-0005](../decisions/0005-single-club-installation.md), and [the unapproved member OpenAPI draft](../contracts/openapi-draft.yaml).
