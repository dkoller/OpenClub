# ADR-0004: MVP membership, visibility and lifecycle policies

- **Status:** Historical accepted baseline; R01 2026-11-15 requirements supersede field, access and MVP-scope assumptions below; privacy approval remains open
- **Date:** 2026-11-14
- **Related:** [R01 #10](https://github.com/dkoller/OpenClub/issues/10), [R03 #12](https://github.com/dkoller/OpenClub/issues/12), [R04 #13](https://github.com/dkoller/OpenClub/issues/13), [privacy finding #36](https://github.com/dkoller/OpenClub/issues/36)

## Confirmed decisions

1. **Club creation:** Configurable per deployment; **approval/invitation required by default**. Deliver the controlled first-admin path first. Superseded: each installation can bootstrap only its one club; no public open-club creation mode exists.
2. **Member onboarding:** Admins can enter people **without accounts**; later add invitation-based onboarding and verified account linking. Never link a person's record to an OIDC account by unverified email alone.
3. **MVP member fields:** First name and last name required; email and phone optional. Keep a person's login identity separate from contact data; do not silently collect birth date, home address or guardian details.
4. **Visibility:** Authenticated, authorized members can read their **own club's** member directory and contact information; only club **ADMIN** users can add/change members or roles. Outsiders and members of other clubs have no access. A member's permissions are scoped to the club being requested.
5. **Lifecycle direction:** Support export, former-member/archive status, erasure/anonymization, and deployment/club-appropriate retention settings **incrementally**. Don't invent a blanket retention duration. Data kept for lawful obligations must be restricted, not treated as an active membership directory.

## Privacy boundary requiring explicit follow-up

The decision to expose contacts to all authenticated members **must be reviewed before real data**. In particular, visibility of children's and young people's contact data needs proportionate defaults, transparency, role limitations where necessary, and a lawful basis. Members should not gain blanket access to other clubs' personal data via a shared `person` record.

The precise contact visibility for minors, controllers/processors, retention schedule, backups, export format, data-subject requests and first real pilot's approval remain **open** under [R03](https://github.com/dkoller/OpenClub/issues/12). The pilot workflow is not yet independently validated.

## Implementation sequence

1. Secure verified BFF sessions and provider configuration; no real-member data prior to privacy decisions.
2. First-admin approval gate plus club-scoped access checks.
3. Minimal member records with required name and optional contacts; use versioned migration and scoped DTOs.
4. Directory read for authenticated members, edits for ADMIN only, with cross-club tests.
5. Verified invitations/account linking, then archive/export/erasure and configurable retention as separate PRs.

Every behavior change must update OpenAPI, docs and tests in its own PR; each PR must stay **at or below 5,000 changed lines** (limit updated 2026-11-15; historical decision text otherwise unchanged).


## Superseding pilot decisions (2026-11-15; R01 closed)

Merged [PR #72](https://github.com/dkoller/OpenClub/pull/72) and [PR #73](https://github.com/dkoller/OpenClub/pull/73) record requirements accepted by the pilot club administrator. The earlier numbered decisions above are kept as historical context but **must not be used as the current member-field or authorization contract** where contradicted:

- **Per-club personal data:** Names, contacts, date of birth, gender and other member details belong to individual club records. Even where a single OIDC identity is associated with several clubs, do not share, synchronize, auto-fill, infer or expose one club's personal data to another. Review ADR-0002's global `person` design before member CRUD.
- **Required fields:** First name, last name and **date of birth** for every person. **Email and phone both required for adults**, optional for minors. Set the applicable age threshold explicitly in R03/R04; do not silently assume a value.
- **Gender:** Optional except when the club explicitly requires it for its players; avoid a sport-based hardcoded default and a binary-only assumption. Define gender options and privacy controls under R03/R04.
- **Authorization:** Configurable capability grants scoped to a club or individual team; **only explicitly permission-authorized club administrators** manage grants. Organizational functions (board, coach, guardian, volunteer, player) are not fixed security roles. Prevent privilege escalation and loss of the final authorized administrator. The old ADMIN/MEMBER model is only an implementation starting point, not the final accepted permission system.
- **First usable release:** Member management, team assignments and configurable permissions, with German, English, French and Italian supported. The detailed Excel inventory/import/migration requirements are intentionally deferred; invitations remain later.

The former policy proposing universal same-club access to contact data is **not privacy approval**. R03 must resolve minors' contact visibility, birth-date/gender access, data minimization, lawful basis and retention before real records. Track the schema, teams and permissions follow-ups in [#74](https://github.com/dkoller/OpenClub/issues/74), [#75](https://github.com/dkoller/OpenClub/issues/75) and [#76](https://github.com/dkoller/OpenClub/issues/76). R04/R06/R10 must specify safe club-local data/permission contracts and tests. These product decisions do not imply functionality is implemented.


## Single-club deployment pivot (2026-11-15)

After R01 acceptance, the owner selected **one club per standalone installation** instead of multi-club tenancy. The per-club member field and configurable permission requirements above remain valid, but each installation owns exactly one club and database. Previous cross-club access and globally shared person concerns are addressed structurally by independent deployments and the removal of multi-club member/profile routing; local privacy/security authorization remains essential. This supersedes multi-club passages in prior ADRs for the first release, with the owner-approved never-live Flyway reset already merged in PR #79. See accepted [ADR-0005](0005-single-club-installation.md) and #74.
