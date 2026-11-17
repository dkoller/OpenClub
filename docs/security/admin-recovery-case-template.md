# Private administrator recovery case — template (never commit a completed copy)

Copy this **blank** template into the club's approved access-controlled, versioned incident register. The repository contains **only the template**, never personal identity, live account identifiers, credentials, evidence attachments or completed records.

## Case opening (before any mutation)

- Case reference (random, non-identifying):
- Installation reference (private inventory ID):
- UTC opened at / reported by (private officer reference):
- Incident category: lost Rauthy passkey / Keycloak account credential / sole-admin account rebind / other (escalate)
- Symptom and compromise indicators (minimal):
- Current service status and impact:
- Account owner's independent verification contact route **pre-existing before incident** (describe method only, no phone/address):
- Identity verifier (independent officer reference):
- UTC verification time and result:
- Evidence register reference (no document images):
- Prior channel unavailable, dispute, minor, or suspected compromise? Yes / No
- Stop decision and escalation reference if any:

## Written authorization (must be completed before execution)

- Proposed provider and exact action:
- Old account / user and named credential fingerprints (private identifiers, never secrets):
- Replacement issuer + subject verified against OIDC, local account reference (rebind only):
- Club + sole ADMIN member references verified independently (rebind only):
- Recovery backup reference, integrity and restoration-readiness verification result:
- Approver (distinct from requester and executor):
- Approval UTC timestamp, record signature/version:
- Approval expiry and explicit permitted action:
- Executor's read-back of scope to approver:
- Denial / disagreement / invalid preconditions: record **NO CHANGE** and escalate

## Execution and witnessed verification (after approval)

- Executor reference / least-privilege access scope / access expiry:
- Mutation UTC start and completion:
- Technical command/procedure reference + result (never output tokens or passwords):
- Fresh login through exact issuer after recovery: pass / fail / not applicable
- Old/stale OpenClub sessions rejected: pass / fail / not applicable
- Old account denied club access: pass / fail / not applicable
- New account assigned only expected permissions: pass / fail / not applicable
- New replacement WebAuthn passkey independently enrolled and MFA tested: pass / fail / not applicable
- Provider sessions and compromised keys revoked per incident policy: pass / fail / not applicable
- Affected admin notified via established channel (UTC / result):
- Witness reference and verification UTC time:
- Unexpected behavior, rollback eligibility and escalation (no raw logs or credentials):
- Final resolution: recovered / aborted without mutation / escalated / separately approved rollback
- Approver final sign-off, UTC time, signed record version:
- Custodian register reference, confidentiality/retention category and access restrictions:

## Synthetic witnessed rehearsal result

- Test installation and exact OpenClub/provider versions:
- Synthetic-only actors and independent human witness roles:
- Disputed/unverified request refused without mutation: pass / fail
- Missing independent approval refused without mutation: pass / fail
- Backup restoration and application permissions verified: pass / fail
- Signed login, session invalidation and replacement factor verified: pass / fail
- Evidence filed privately; owner acceptance (date and register reference):

**Never include:** passwords, MFA one-time codes, private keys, session cookies, identity-document scans, raw database dumps, recovery API keys, real personal data in GitHub.
