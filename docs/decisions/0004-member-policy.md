# ADR-0004: MVP membership, visibility and lifecycle policies

- **Status:** Accepted product direction; release-specific privacy and operational details still require approval
- **Date:** 2026-11-14
- **Related:** [R01 #10](https://github.com/dkoller/OpenClub/issues/10), [R03 #12](https://github.com/dkoller/OpenClub/issues/12), [R04 #13](https://github.com/dkoller/OpenClub/issues/13), [privacy finding #36](https://github.com/dkoller/OpenClub/issues/36)

## Confirmed decisions

1. **Club creation:** Configurable per deployment; **approval/invitation required by default**. Deliver the controlled first-admin path first. An installation operator can choose an open new-club creation mode in a later phase, but neither mode permits taking over an existing club.
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

Every behavior change must update OpenAPI, docs and tests in its own PR; each PR must stay **below 1,000 changed lines**.
