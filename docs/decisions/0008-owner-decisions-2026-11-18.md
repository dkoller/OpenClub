# ADR-0008: Owner decisions on M2 contract, recovery, member sign-in, privacy proposal and accessibility audit

- **Status:** Accepted by the repository owner on 2026-11-18. Each decision was asked one at a time and answered by the owner; the wording of what each answer does **not** approve is the agent's, and the owner may correct it.
- **Scope:** Delivery gates and product scope. This is **not** legal approval and does **not** authorize real member data, a production deployment, spending or any security or privacy exception.

## Decisions

| # | Topic | Decision | Issue |
| --- | --- | --- | --- |
| 1 | Privacy, retention and data lifecycle | The owner asked the agent to **draft a proposal**. It is drafted in [privacy proposal 2026-11-18](../discovery/privacy-proposal-2026-11-18.md) and is **not yet decided**: every value in it is a proposed default for the owner and the controller to approve or change. | [#12](https://github.com/dkoller/OpenClub/issues/12) |
| 2 | M2 contract draft | The owner **approved [`openapi-m2-draft.yaml`](../contracts/openapi-m2-draft.yaml) as is** (full confidential member replacement and HTTP reactivation, still `0.0.0-draft` and not implemented). This approves the draft as the target contract. It does **not** approve real data, release or any implementation: the M2 prerequisites already recorded in [ADR-0007](0007-owner-decisions-2026-11-17.md) (privacy decisions, audit #26, security review) still apply. A later change to the frozen M1 surface still needs a new owner-approved version. The draft contains only these two operations: it has **no** contact-read, birth-date-read, export or erasure operation, so those would need a draft extension that the owner approves (see the privacy proposal, section 6). | [#13](https://github.com/dkoller/OpenClub/issues/13) |
| 3 | Sole-administrator recovery | The owner **approved the documented procedure as written** ([admin-recovery-procedure.md](../security/admin-recovery-procedure.md) and the [case template](../security/admin-recovery-case-template.md)). This approves the written procedure only. **No witnessed drill has been performed**, so recovery evidence for real data (M2) is still missing. | [#180](https://github.com/dkoller/OpenClub/issues/180) |
| 4 | Members signing in themselves | The owner decided members **must be able to sign in themselves for the pilot**. Invitations and verified account linking (previously deferred) are therefore in scope. A design is drafted in [invitation and account-linking design](../security/invitation-linking-design.md) for approval **before any code**. Whether the hosted M1 synthetic demo must include it is **not decided**; until it is, M1 acceptance still rests on administrator-managed accountless members. | [#20](https://github.com/dkoller/OpenClub/issues/20) |
| 5 | Third-party security review | The owner reports that an independent third-party security review was done and found nothing worth fixing. Reviewer: **secureIt AG**; date: **2026-11-18**, as given by the owner. Only that statement is recorded: the report is not in the repository and was not read by the agent, and the scope of the review was not provided and is therefore **not recorded**. | [#24](https://github.com/dkoller/OpenClub/issues/24) |
| 6 | Manual accessibility audit | The owner decided to **skip** a manual screen-reader, focus-order and contrast audit. The accepted evidence is the automated browser checks only (one `main`, named regions, document `lang`, accessible control names, resolving ARIA references, keyboard-only member search and archive cancel). This is **not** an accessibility conformance claim. | [#25](https://github.com/dkoller/OpenClub/issues/25) |

## Consequences

- **M1 is still blocked** on the hosted demo: operator and OIDC provider are not chosen ([ADR-0007](0007-owner-decisions-2026-11-17.md), decisions 1a and 1c).
- **Not started:** any implementation of the M2 draft operations or of invitations. The proposals need the owner's approval of the values and design first.
- **Still open for M2:** controller, jurisdiction and lawful basis, retention values, notices, minors' safeguards, export/erasure/audit (#26), backups, a witnessed recovery drill and operator sign-offs.

## Addendum — later on 2026-11-18: approval of the proposed defaults

After the two drafts were merged, the owner said: **"Approve all defaults, start the audit table."** The agent records the scope of that approval as follows; the owner may correct it.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 7 | [Privacy proposal](../discovery/privacy-proposal-2026-11-18.md), section 7 | The owner **approves the proposed default** for every row that has one: contact visibility (3), minors' contacts (4), archived retention of 24 months then erase (5), erase as hard delete after archive (6), 30-day request deadline (7), 30-day rolling backups (8), 12-month audit retention with no personal data in events (9) and deleting accounts without a login after 90 days (**later changed to 2 years, decision 14**) (10). **Not approved, because the proposal gives no default and they are facts or legal questions for the controller:** jurisdiction, controller, processors and hosting location (1), and lawful basis and notices (2). The values above are the owner's product defaults; the controller's approval is still required before any real member data. |
| 8 | [Invitation design](../security/invitation-linking-design.md), "Decisions needed" | The owner **approves the proposed default** for invitation life of 7 days (2), who may invite, revoke and unlink (3), administrator confirmation after acceptance for minors only (4) and hand-over of the link by the administrator with no system email (6). **Still undecided, because they carry no default:** whether invitations belong in the hosted M1 demo or only the pilot (1), minors signing in themselves versus a guardian (5), and approval of a new contract version for the four operations (7). No invitation code is written before (7). |
| 9 | Audit table | The owner asked the agent to start with the audit table (privacy proposal, section 8, step 2). It is implemented in Flyway V12 and wired into the existing member, role, grant and recovery operations; see [club-membership-access.md](../security/club-membership-access.md). |

**Not changed by this addendum:** no real data is authorized; the controller's decisions, a new contract version for contact reads, export, erasure and invitations, and the remaining implementation steps (archive-then-erase, export, contact reads, retention jobs, invitations) are still to be approved and built one pull request at a time.

## Addendum 2 — later on 2026-11-18: invitations in the M1 demo, guardians, contract draft

Asked one set of questions at a time, the owner answered as follows. The agent's wording of consequences may be corrected by the owner.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 10 | Invitations in the hosted M1 demo ([invitation design](../security/invitation-linking-design.md), decision 1) | **Included.** The M1 synthetic demo must show a second synthetic user accepting an invitation. This changes the earlier statement that M1 rests on administrator-managed accountless members. M1 acceptance still waits on the operator and provider (ADR-0007, 1a and 1c). |
| 11 | Who signs in themselves (decision 5) | **A guardian signs in for a minor.** The privacy proposal excludes guardian relationships until a separate requirement and privacy review, so a **guardian design is drafted and reviewed before any code** that lets someone act for a minor. Adult members signing in themselves (the design as written) is not blocked by this. |
| 12 | Contract extension (decision 7) | **The agent may draft an unversioned extension of the M2 draft and build against it.** The owner approves the contract text in the pull request; the agent merges code for an operation only after that approval is recorded there. The frozen 1.1.0 M1 surface changes only with a new owner-approved version. |
| 13 | Retention jobs | Implemented (V13, `RetentionCleanup`): audit events older than 12 months and accounts with no linked member and no login for **2 years** (changed from 90 days by decision 14) are deleted in bounded hourly batches. The controller's approval is still required before real data. |
| 14 | Orphan-account retention | The owner said: **"Not a 90 day inactivity rule, make it 2 years."** The rule for accounts with no linked member is therefore **2 years without a login**, replacing the 90-day default the owner approved in decision 7 (row 10). The earlier text is kept as the historical record. |

## Addendum 3 — contract extension drafted (awaiting approval)

Following decision 12, the agent drafted the extension in [`openapi-m2-draft.yaml`](../contracts/openapi-m2-draft.yaml): `getLocalMemberDetails`, `eraseArchivedLocalMember`, `createLocalMemberInvitation`, `revokeLocalMemberInvitation`, `acceptInvitation` and `unlinkLocalMemberAccount`, all marked NOT IMPLEMENTED. **It is a proposal: no code for any of these operations is merged until the owner approves the text in the pull request and the approval is recorded here.** A guardian design for review is in [guardian-design.md](../security/guardian-design.md); guardians are not part of this extension.

## Addendum 4 — contract text approved, guardian design privacy-reviewed

The owner replied on pull request #338: **"Approved. And privacy review ok."** The agent records the scope as follows; the owner may correct it.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 15 | Contract extension text | The owner **approved the six operations as drafted** in [`openapi-m2-draft.yaml`](../contracts/openapi-m2-draft.yaml): `getLocalMemberDetails`, `eraseArchivedLocalMember`, `createLocalMemberInvitation`, `revokeLocalMemberInvitation`, `acceptInvitation` and `unlinkLocalMemberAccount`, including the details the agent listed: an unreadable field is absent and null means "readable and not set", 409 when an invitation is already open or the member is linked, one generic 400 `invalid` for every acceptance failure, and the new audit events (`CONTACT_READ`, `INVITATION_CREATED`, `INVITATION_ACCEPTED`, `INVITATION_REVOKED`, `ACCOUNT_UNLINKED`). Code for each operation may now be written and merged. This is a contract approval, not an approval of real data. |
| 16 | Contract version | The agent proposed that operations leave the draft and join the frozen surface as **OpenAPI 1.2.0** when implemented, and the owner approved ("Approved") the message that contained this proposal. Each implementing pull request moves its operations from the draft into `openapi-v1.yaml`, bumps the version once per release of the surface, and updates the drift check. Reactivation and member replacement (`PUT`) stay in the draft: replacement still needs the detail read in use first. |
| 17 | Guardian design | The owner's **privacy review of the [guardian design](../security/guardian-design.md) is OK**: its proposed defaults (decisions 1 to 6 in that document) stand as the owner's product decisions. **Not covered, because it is for the controller and counsel:** the lawful basis and notices for a guardian's access to a child's data (point 1 of its privacy list) and the other open privacy points the design assigns to them. A guardian contract extension (operations, schema) is still to be drafted and approved before any guardian code, as the design's order of work says. No real data is authorized. |

**Consequences:** the invitation slice, unlink, the restricted detail read and erasure over HTTP are unblocked and are built one pull request at a time. Guardian operations still need their own contract draft.


## Addendum 5 — full member edit and reactivation approved (2026-11-19)

Asked in the Claude Code session that built pull request #342 whether `replaceLocalMemberDetails` (`PUT /v1/members/{memberId}`) and `reactivateLocalMember` (`POST /v1/members/{memberId}/reactivate`) may leave the draft, the owner chose **"Approve both"**. In the same session the owner decided that **erasure has no browser UI in M1** (the operator command stays the M1 erasure path) and asked for larger pull requests that bundle several steps.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 18 | Full member edit | Approved as drafted: an editor with `EDIT_MEMBERS` replaces every editable field of an active member; omitted contacts and gender clear them, so the browser reads the current values through the restricted detail read first. Database rules stay authoritative (400). |
| 19 | Reactivation | Approved as drafted: restores only active status, never grants, teams or a login link; the record is validated against the current club policy (409 if it no longer fits); audited as the new `MEMBER_REACTIVATED` event (V18). |
| 20 | Contract version | Both join the frozen surface as **OpenAPI 1.4.0**; the M2 draft keeps no operation for now. |
| 21 | Erasure UI | None in M1; the HTTP route serves API clients and the operator command remains the M1 path. An archived-member listing would be a new contract change. |

None of this authorizes real data.

## Addendum 6 — hosting provider and backups for the synthetic demo (2026-11-19)

In the Claude Code session after pull request #343 the owner stated: **"The hosting provider is Infomaniak"** and **"No backup for now."**

| # | Topic | Recorded decision |
| --- | --- | --- |
| 22 | Hosting provider for the M1 demo | **Infomaniak** (Switzerland). The agent recommends VPS Lite 2 vCPU / 4 GB / 60 GB at CHF 7.20 per month (price checked 2026-11-19), inside the ≤ €10/month of ADR-0007; the invoiced amount is the evidence, not this estimate. Who operates the host is still open. |
| 23 | Backups | **None for the synthetic demo for now.** Losing the server loses the fictional demo data. This does not change the M2 gates: real member data still needs backups, a witnessed restore and recovery sign-off (#12, #26, #180). |
| 24 | Identity provider on the host | Not decided by the owner. The [Infomaniak runbook](../deployment/infomaniak-demo.md) uses **Rauthy** on the same VPS because it fits in 4 GB next to the API and PostgreSQL; Keycloak stays supported (ADR-0006) with an external or larger host. |
| 25 | Demo email and tooling | Asked to bundle a one-command deploy, fictional demo data and email, the owner answered **"Go with email"**: Rauthy sends email through an Infomaniak mailbox (optional overlay, configured on the host; credentials never in the repository). |

None of this authorizes real data.

## Addendum 7 — archived-member search (2026-11-19)

Asked in the Claude Code session whether `GET /api/v1/members/archived` (name search over archived members for `EDIT_MEMBERS`, items: id, names and archive time only) may be added as OpenAPI 1.5.0, the owner chose **"Approve"**; asked whether that list should also offer *Erase permanently*, the owner chose **"Reactivate only"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 26 | Archived-member search | Approved as OpenAPI **1.5.0**: editors find members archived earlier; no birth dates, contacts or account data in the list (the restricted detail read serves editors those). |
| 27 | Erasure UI | Confirms decision 21: the *Archived members* panel offers **Reactivate only**; erasure stays an operator command in M1. |

None of this authorizes real data.

## Addendum 8 — guardian contract approved and built (2026-11-19)

Asked in the Claude Code session to approve the six guardian operations drafted from the privacy-reviewed [guardian design](../security/guardian-design.md), the owner chose **"Approve, build it"**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 28 | Guardian operations | Approved as drafted and implemented as OpenAPI **1.6.0**: editors (`EDIT_MEMBERS`) list, add (their attestation, at most two, active adults with a login) and remove a minor's guardians; a guardian lists their minors, reads one (names, birth date, email, phone, player flag, team names; audited) and replaces only email and phone. No capability is granted; access ends at the club's adult age, on archive, unlink, erasure or removal. |
| 29 | Still open | Lawful basis and notices for a guardian's access to a child's data (controller and counsel). This approval covers synthetic data only. |

## Addendum 9 — demo operator (2026-11-19)

Asked who operates the Infomaniak demo server (orders the VPS, holds root and the generated secrets, runs the deploy script), the owner answered **"Me (the owner)"**; the two hostnames are **not decided yet**.

| # | Topic | Recorded decision |
| --- | --- | --- |
| 30 | Demo operator | **The owner** operates the synthetic demo host and runs the [M1 acceptance walkthrough](../deployment/m1-acceptance-walkthrough.md). This replaces "operator not yet chosen" in ADR-0007 and decision 22. |
| 31 | Hostnames | Open; the runbook keeps `club.example.ch` and `id.example.ch` placeholders. |
