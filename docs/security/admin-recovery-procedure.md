# Administrator account recovery: identity, approval and audit

**Status:** **Approved as written by the repository owner on 2026-11-18** ([ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md), #180). This approves the written procedure only: **no witnessed drill has been performed** and it is **not approved for real member data**. Applies to a single-club OpenClub installation. Follow this procedure for loss of the **sole club administrator's OpenClub account**. For lost Rauthy passkeys where the same account can safely be recovered by an identity-provider administrator, use the separate provider path below. Keycloak remains an independently supported OIDC provider.

**Authority boundary:** Only authorized club officers decide *who* is entitled to recover an administrator identity. The infrastructure operator executes an approved technical change but cannot establish entitlement merely by owning database, Rauthy or Keycloak credentials. OpenClub's `recoverSoleAdmin` CLI enforces database invariants and session revocation; it does **not** record human consent or verify documents. The Rauthy browser test uses synthetic accounts, not a production approval process.

## Appoint operators before an incident

Record the following in a private club-owned access register, **not** in the Git repository, issue, PR, CI artifact or shared chat. Review after each role change and at least annually.

| Responsibility | Required separation |
| --- | --- |
| Recovery requester | Account holder or documented authorized club representative; cannot self-authorize an admin takeover |
| Identity verifier | Known officer who checks the claimant using a previously recorded independent channel; cannot be the sole approver |
| Independent approver | Second authorized officer, distinct from requester and infrastructure executor; signs off on the exact target account/action |
| Technical executor | Operator with controlled database/provider access; must not approve their own execution |
| Witness/record custodian | Checks postconditions and ensures the incident record is filed; may be the independent approver but not the executor |

For a one-person or disputed club, **no break-glass shortcut is permitted**. Escalate to the club's governing body or previously documented external authority and suspend changes until independence can be established.

Use named, individual accounts, MFA and least-privilege, time-bounded infrastructure/provider access. Maintain a replacement contact and a secure process to revoke departing operators. Do not put privileged API keys in OpenClub or let a club-role grant become an identity-provider administrator credential.

## Required steps for every recovery

1. **Open a private case.** Allocate a random case ID in the club's restricted incident register. Record UTC timestamps, reported symptom and the affected installation, without copying passwords, recovery codes, OTPs or ID documents. Alert the on-call security contact for suspected compromise.
2. **Verify identity out of band.** Contact the claimant via **at least one contact method established before the incident** (e.g. a registered phone number reached by a fresh call, plus an in-person check or another established officer). A caller-provided replacement email/number, display name, club membership alone, possession of an email inbox, or a newly presented ID scan is insufficient by itself. Record the **method and outcome**, not identity-document images. If prior channels are lost, identity contested, or the member is a minor, stop for an authorized human resolution; do not improvise.
3. **Approve the precise action.** Independent approver checks the identity-verification evidence and signs the private record with the case ID, affected installation, existing account/credential fingerprint, proposed replacement identity, least-privileged action, backup status, impact, and expiry of authorization. The executor must not be the approver. Both approvals occur **before** any mutation.
4. **Establish a recovery point.** Confirm current service health, identify the sole-admin invariant where relevant, take a recoverable encrypted/private database backup according to the approved operator process and verify that the restore procedure and key access are available. Record the backup **reference and verification result**, not the archive or encryption key. If no independently verifiable backup is available, do not execute a sole-admin rebind.
5. **Confirm exact identity.** For the OpenClub account rebind, the replacement **must first authenticate normally through the configured OIDC issuer**; inspect the verified `issuer` and `subject` in `account_identities` and match the corresponding internal account UUID. Confirm the precise club, member and old-account UUID from the database. **Never link by email, display name, surname, or a user-supplied UUID alone.** For an IdP-only passkey recovery, verify provider user ID and exact registered credential name before revocation; do not change local OpenClub memberships.
6. **Execute one scoped operation.** Use the restricted method for the applicable scenario below. Do not export full user tables, share secret-bearing logs or broad admin credentials, or run commands from an untrusted workstation. Capture only the command's success/failure status, timestamp, executor, target fingerprint, and approved case ID in the private register. On any mismatch or partial failure, stop; do not try alternate accounts until reapproved.
7. **Verify and close.** Witness a fresh login and expected club rights, rejection of old/stale app sessions and denial of old account access. For Rauthy key recovery, enroll a new factor on the replacement device and verify actual MFA before closing. Verify unrelated member permissions remain unchanged, rotate/revoke compromised provider sessions/credentials as appropriate and notify the affected administrator through an established channel. Log results and the rollback/escalation decision; have approver and custodian sign off.

### Which technical operation?

| Situation | Correct action | Must not do |
| --- | --- | --- |
| Rauthy passkey lost, same account and password are still independently verified | Authorized provider administrator revokes **only the named lost passkey**, then user logs in and enrolls a new WebAuthn factor. See [Rauthy local acceptance](../development/rauthy-local.md). | Do not use a production copy of the CI-only API key, reset an unrelated factor, or automatically downgrade MFA |
| Keycloak credential lost, same verified identity is recoverable | Use the provider's approved administrator recovery and session-revocation process, separately verified for the deployed Keycloak version. | Do not apply Rauthy API instructions to Keycloak or silently remap `sub` |
| Sole OpenClub administrator permanently needs a different verified OIDC account | Run the [restricted sole-admin rebind](../deployment/single-club.md#operator-only-recovery-of-the-sole-administrator), **only** after steps 1–5. The CLI atomically revokes old and replacement app sessions. | Do not execute direct ad-hoc SQL, create a second admin, or use email-only account matching |
| Multiple administrators, contested ownership, suspected active attacker, inconsistent DB or unknown issuer | **Stop** and escalate to security lead and independent club authority; restore only under separately reviewed recovery decision. | Do not bypass the one-admin guard or use the emergency command as normal role management |

## Evidence and retention

Maintain one restricted record per incident using [the private case template](admin-recovery-case-template.md). It must include unique case ID; requester, verifier, approver, executor, witness (as **private register references**); timestamps; pre-existing-channel verification result; scoped action; exact-account/credential fingerprints; backup verification; before/after access checks; observed failures; member notification; and the final resolution. Keep a tamper-evident history in the club's approved private record system (append-only/versioned log with access logging or signed and independently archived records). **Do not create a mutable plaintext audit file in this repository** or treat GitHub CI and application logs as the approval ledger.

Keep only the minimum data needed to demonstrate authorization. The controller must decide restricted access, retention duration, correction/deletion rules and secure disposal under [privacy issue #12](https://github.com/dkoller/OpenClub/issues/12) before real data. Share case evidence only with authorized club officers and incident responders.

## Drill and sign-off before real members

Perform a witnessed **synthetic-only** rehearsal with two independent people and a fresh test identity: test both allowed recovery paths as applicable; save a private case record; verify backup/restore, fresh signed login, 401 for old/stale sessions, 403 for unlinked accounts, unchanged grants, and a real replacement factor. Simulate a disputed request and missing approver to confirm the procedure **stops without mutation**. Record exact software versions, timestamps, expected and actual outcomes, and each reviewer’s sign-off. A passing GitHub test alone does not establish the two-person operational drill.

**Release gate:** [#180](https://github.com/dkoller/OpenClub/issues/180) remains open until accountable club officers are nominated, independent approvals and witnessed drill evidence exist, and the privacy/hosting/backup gates [#12](https://github.com/dkoller/OpenClub/issues/12) and [#27](https://github.com/dkoller/OpenClub/issues/27) are approved. No software change can invent these real-world approvals.
