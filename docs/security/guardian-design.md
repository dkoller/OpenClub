# Guardians signing in for a minor — design for privacy review

**Status: the owner's privacy review of this design is OK (2026-11-18, [ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md) addendum 4, decision 17), so its proposed defaults stand as the owner's product decisions. The controller's and counsel's points (lawful basis, notices) are still open, the guardian contract operations are not drafted, and there is no code.** On 2026-11-18 the owner decided that **a guardian signs in for a minor** ([ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md), addendum 2, decision 11). The [privacy proposal](../discovery/privacy-proposal-2026-11-18.md) excludes guardian relationships until a separate requirement and privacy review, so this design must be reviewed and approved **before any code that lets one person act for a minor**. Adults signing in themselves ([invitation design](invitation-linking-design.md)) is a separate, unblocked track.

## What exists today

- A member has at most one linked sign-in account, and an account at most one member. A minor can be an accountless member with optional contacts; the minor's name is shown publicly only as first name plus surname initial.
- Archiving refuses linked members. A member is a minor when the server computes an age below the club's configured adult age (default 18) from the stored birth date.
- There is **no relationship between two members**: no parent, no guardian.

## Proposed model

The guardian signs in with **their own account**, linked to their **own adult member record** through the normal invitation flow. A new, explicit **guardianship** relates that adult member to one minor member. Binding the minor's record to the guardian's account is rejected: it would give the guardian the minor's identity, break "one account, one member" and make the minor's later own login impossible.

| Aspect | Proposed default (to be decided) |
| --- | --- |
| Creating a guardianship | An administrator holding `EDIT_MEMBERS` records it after verifying the guardian's relationship **outside** OpenClub. The system cannot verify legal guardianship; the administrator attests, and the attestation is audited (`GUARDIAN_LINKED`: who, which two members, when). No self-service claim by a guardian. |
| Who can be a guardian | An **active adult member** with a linked verified account. Never the minor, never an archived member. |
| Guardians per minor | At most **two**. |
| What a guardian may do | **Read** the minor's own record: names, date of birth, contacts, team memberships, player flag. **Edit the minor's contact details.** Nothing else: no roles, grants, other members, other minors, no archiving or erasure. |
| What a guardian never gets | Capabilities. Being a guardian grants no club or team capability; the guardian's own capabilities are unchanged and independent. |
| Each guardian read | Audited (`DEPENDENT_READ`, identifiers only), like a team manager's contact read. |
| Ending | Automatically when the minor reaches the club's adult age (the check runs on every read, so a birthday or a raised adult age takes effect immediately, and a lowered adult age is only restrictive); when the guardian or the minor is archived, unlinked or erased (cascade); or when an administrator revokes it (`GUARDIAN_UNLINKED`). A revoked guardian loses access at the next request. |
| The minor's own login | At the adult age an administrator can invite the now-adult member normally. Until then the minor has none. |

## Authorization shape

Access is by **relationship**, not by capability. A new check, "this account's member is a current guardian of that minor", is evaluated inside the same SQL statement that reads or writes (as the capability checks are), so a revoked relationship, an archived guardian or a revoked identity is denied at once. Routes sit under the signed-in person (for example "my dependents" and "a dependent's details"), never under an administrator route, so a guardian never reaches member lists. All refusals are one generic 403, authorize before validate, and no response reveals whether a minor exists.

## Privacy points the review must settle

1. **Lawful basis and notice** for the guardian's access to the child's data, and for processing the guardian's own data: for the controller and counsel; nothing here assumes either.
2. **Disputes and separations:** who decides when guardians disagree. Proposed: an administrator can revoke either guardian at any time, with the reason kept in the private register, not in the system.
3. **A minor's own rights** as they approach adulthood; whether a minor aged 13 or more should see what the guardian sees (not proposed).
4. **Evidence of guardianship:** whether the club needs a record beyond the administrator's attestation, and where it lives (not in the repository).
5. **Retention:** a guardianship disappears with either member. Audit events about it follow the 12-month audit rule.
6. **Safeguarding:** a guardian reading a child's record is an access path; the audit trail and a revocation procedure must exist before real data.

## Decisions needed

| # | Decision | Proposed default |
| --- | --- | --- |
| 1 | Administrator attests guardianship, no self-service claim | Yes |
| 2 | Guardians per minor | At most 2 |
| 3 | Guardian scope | Read the minor's record and edit the minor's contacts only |
| 4 | Ends at the club's adult age automatically | Yes |
| 5 | Minors aged 13 or more see their own record | No |
| 6 | Who may create and revoke | `EDIT_MEMBERS` |
| 7 | Everything under "Privacy points" | Controller and counsel |

## If approved: order of work

1. Extend the M2 contract draft with the guardian operations; the owner approves the text.
2. A guardianship table, its checks and audit events, with synthetic PostgreSQL tests (a revoked guardian, an adult-age transition, an archived guardian, two guardians, the cascade).
3. Authorization checks and HTTP routes with the authorize-before-validate and revocation matrices used for the M1 routes.
4. Four-language UI and a browser journey with a second synthetic user.
Each step is its own pull request, and none touches real data.
