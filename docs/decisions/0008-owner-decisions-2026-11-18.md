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
| 5 | Third-party security review | The owner reports that an independent third-party security review was done and found nothing worth fixing. Only that statement is recorded; the report is not in the repository and was not read by the agent. The reviewer's name, date and scope are to be added when the owner provides them. | [#24](https://github.com/dkoller/OpenClub/issues/24) |
| 6 | Manual accessibility audit | The owner decided to **skip** a manual screen-reader, focus-order and contrast audit. The accepted evidence is the automated browser checks only (one `main`, named regions, document `lang`, accessible control names, resolving ARIA references, keyboard-only member search and archive cancel). This is **not** an accessibility conformance claim. | [#25](https://github.com/dkoller/OpenClub/issues/25) |

## Consequences

- **M1 is still blocked** on the hosted demo: operator and OIDC provider are not chosen ([ADR-0007](0007-owner-decisions-2026-11-17.md), decisions 1a and 1c).
- **Not started:** any implementation of the M2 draft operations or of invitations. The proposals need the owner's approval of the values and design first.
- **Still open for M2:** controller, jurisdiction and lawful basis, retention values, notices, minors' safeguards, export/erasure/audit (#26), backups, a witnessed recovery drill and operator sign-offs.
