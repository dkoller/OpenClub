# Invitations and verified account linking — design for owner approval (#20)

**Status: defaults approved by the owner on 2026-11-18 for decisions 2, 3, 4 and 6 ([ADR-0008 addendum](../decisions/0008-owner-decisions-2026-11-18.md), decision 8). Later the same day the owner decided that invitations are part of the hosted M1 demo (decision 1), that a guardian signs in for a minor (decision 5; a guardian design and privacy review come before code for minors) and that the agent may draft the contract extension and build against it once the owner approves its text (decision 7; ADR-0008 addendum 2). Not implemented.** The owner decided on 2026-11-18 that members must be able to sign in themselves for the pilot ([ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md), decision 4), so the previously deferred [#20](https://github.com/dkoller/OpenClub/issues/20) is in scope. This design needs the owner's approval, and a new contract version, before any code. Whether the hosted M1 synthetic demo must include it is **not decided**.

## What exists today

- A person signs in with an OIDC provider. The API resolves the verified issuer and subject to an installation-local **account**. A first login creates an account with **no member**: authentication alone grants no club access (`VerifiedAccountsTest`).
- Members are created by an administrator without an account (`members.account_id` is empty). `members.account_id` is unique, and an account has at most one member.
- The only code that sets `members.account_id` is operator-only: first-club bootstrap and sole-administrator recovery. **There is no member-level "link" and no "unlink".** Archiving refuses linked members, so a linked member must be unlinked first and no such operation exists.
- Email or name equality never adopts a member. That rule stays.

## Principles

1. **Two proofs, never one.** Linking needs the invitation secret **and** a verified OIDC login in the same request. The secret alone does nothing; a login alone does nothing.
2. **No identity guessing.** Never link by matching email, name or birth date.
3. **Single use, short life, revocable.** One open invitation per member; it dies on first use, on expiry, on revocation or when the member is archived.
4. **Linking grants nothing.** The linked member keeps role `MEMBER` and no capabilities. Capabilities stay a separate administrator decision.
5. **No secret at rest, no secret in logs.** Only a hash of the secret is stored; the secret is shown once to the inviting administrator.
6. **Nothing leaks about members.** Failures answer one generic code, so a caller cannot tell "wrong", "expired", "used" or "revoked".

## Flow

1. An administrator with `EDIT_MEMBERS` opens an **active, unlinked ordinary member** and creates an invitation. The API returns a link once: `https://<host>/invite#<secret>`. The secret is 256 random bits; the fragment is never sent to the server by the browser, so it stays out of access logs.
2. The administrator gives the link to the person **outside OpenClub** (no email is sent by the system; this also avoids unsolicited mail in development and any SMTP dependency).
3. The invitee opens the link and signs in with the club's OIDC provider.
4. The page sends the secret in the body of `POST /v1/invitations/accept` with the session cookie, the exact Origin and the CSRF token. In **one transaction** the server locks the invitation, checks it is open, unexpired and unrevoked, locks the member row and checks it is still active, unlinked and a plain `MEMBER`, and checks that the signed-in account has no member. It then sets `members.account_id`, marks the invitation used, and writes an audit event.
5. The invitee now sees the club as that member, with names-only access until an administrator grants more.

## Anti-takeover

| Threat | Control |
| --- | --- |
| Link intercepted or shared | Short life (proposed 7 days), single use, and the administrator hands it over out of band. If the wrong person accepted, an administrator unlinks and re-invites. The audit event shows which account accepted. |
| Attacker guesses a secret | 256 random bits; lookup by hash; admission-rate limit on the accept route like the other auth routes. |
| Replay after use or revocation | The accept is one conditional `UPDATE … WHERE used_at IS NULL AND revoked_at IS NULL AND expires_at > now()`; two racing accepts have exactly one winner. |
| Account already linked, or member already linked or archived | Refused with the same generic code. |
| Invitation for an administrator | Refused: administrator accounts are linked only by operator bootstrap and recovery. |
| Password-reset-style confusion with email | Email is never an input. |
| Secret in logs, history or referrer | Fragment link; secret only in a POST body; never logged; the web app removes it from the address bar after reading it. |

## Revocation and recovery

- **Revoke an open invitation:** an administrator with `EDIT_MEMBERS`.
- **Unlink an account from a member** (new operation, needed before archiving and for a lost login): an administrator with `MANAGE_PERMISSIONS`, because it changes who can sign in. It deletes that account's browser sessions, clears the member's role grants (as archiving does) and refuses the last eligible permission manager, reusing the existing advisory lock. After unlinking, the member can be re-invited.
- **Lost provider login:** unlink and re-invite. **Sole administrator:** the existing, owner-approved [recovery procedure](admin-recovery-procedure.md); invitations never replace it.

## Proposed contract and schema (needs a new approved version)

The four operations below are now drafted, as proposals marked NOT IMPLEMENTED, in [`openapi-m2-draft.yaml`](../contracts/openapi-m2-draft.yaml) (`createLocalMemberInvitation`, `revokeLocalMemberInvitation`, `acceptInvitation`, `unlinkLocalMemberAccount`); the owner approved that text on 2026-11-18 ([ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md) addendum 4), so code may now be written. **Built so far (V17): the invitation and unlink services and their four HTTP routes (OpenAPI 1.2.0) with tests; the browser UI is not built yet**, see [club-membership-access.md](club-membership-access.md#invitations-and-account-unlinking-v17-services-and-http-routes-built). Minors are not invited: the owner decided a guardian signs in for a minor. The draft shows the member's invitation status through the proposed detail read (`getLocalMemberDetails`) so the frozen 1.1.0 member routes stay unchanged.

- New operations, which would extend the contract as a version bump: `POST /v1/members/{memberId}/invitation` (create, returns the link once), `DELETE /v1/members/{memberId}/invitation` (revoke), `POST /v1/invitations/accept`, `DELETE /v1/members/{memberId}/account-link` (unlink). Member detail would gain a `linked` flag and the invitation status for editors.
- Flyway V12: an `invitations` table with member reference, secret hash, creator, created, expires, used and revoked times, and a partial unique index allowing one open invitation per member. Raw lengths bounded like the other tables.
- Audit events (see the [privacy proposal](../discovery/privacy-proposal-2026-11-18.md), section 5): invitation created, accepted, revoked; account unlinked.

## Tests required before merge

Wrong, replayed, expired, revoked and other-member secrets; secret without login and login without secret; two accounts racing one secret; the same account accepting two invitations; archived, linked and administrator targets; unlinking the last permission manager; sessions deleted on unlink; authorize-before-validate and revoked-identity matrices as for the M1 routes (#24); a browser journey in four languages with a second synthetic user; invitation secrets never appearing in logs or responses.

## Decisions needed

| # | Decision | Proposed default |
| --- | --- | --- |
| 1 | Is it part of the hosted M1 demo, or only the real-data pilot (M2)? | Not decided: M1 acceptance currently rests on administrator-managed members |
| 2 | Invitation life | 7 days |
| 3 | Who may invite, revoke and unlink | Invite and revoke: `EDIT_MEMBERS`. Unlink: `MANAGE_PERMISSIONS` |
| 4 | Extra step: administrator confirms the link after acceptance, especially for minors | Recommended for minors' records; off for adults |
| 5 | Minors signing in themselves, or only a guardian | Not proposed; needs the controller (privacy proposal, minors' safeguards) |
| 6 | Delivery | Administrator hands the link over; no system email |
| 7 | Approve a new contract version for these operations | Needed before code |

## Order of work if approved

1. Contract version with the four operations; owner approves it.
2. V12 and the accept, revoke and unlink services with the concurrency tests.
3. HTTP routes with the denial matrices.
4. Four-language UI for administrators and invitees, then the browser journey.
Each is its own pull request under the size limit.
