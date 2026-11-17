# OpenClub delivery roadmap

**Current delivery status (2026-11-17).** OpenClub is a pre-production, **one-club-per-installation** application using an independent PostgreSQL database. The synthetic member, team, role and permission workflows and German, English, French and Italian administration UI are implemented and exercised in CI. **Neither M1 hosted acceptance nor M2 real-data release has been approved.**

This is a **current delivery map**, not an implementation changelog. For the source of truth on work and acceptance checklists, use [open issues](https://github.com/dkoller/OpenClub/issues). For dated implementation evidence and the original R01–R22 sequencing, use the [archived roadmap snapshot](history/roadmap-2026-11-17.md). Original [first-milestone proposal](concepts/ai/first-milestone.md) and [MVP proposal](concepts/product/mvp.md) are historical.

## Accepted architecture and product scope

- **One club per installation**, no global person directory, club switcher or cross-club grants: [ADR-0005](decisions/0005-single-club-installation.md). Earlier [ADR-0002](decisions/0002-identity-and-membership-model.md) is superseded.
- **Both Keycloak and Rauthy remain supported** as alternative OIDC providers for an installation: [ADR-0006](decisions/0006-oidc-provider-choice.md). Disposable Rauthy test credentials and `LOCAL_TEST` are not public-hosting settings.
- A club administrator can use synthetic member search/create/name edit/archive, teams and assignments, role changes, scoped grant/revoke and basic club settings. Public display names follow the accepted rule: adults' full names; minors' first name and surname initial. Private fields and production privacy controls need independent approval.
- First-release scope includes teams and configurable permission grants ([#75](https://github.com/dkoller/OpenClub/issues/75), [#76](https://github.com/dkoller/OpenClub/issues/76)); invitations/account linking remain distinct work ([#20](https://github.com/dkoller/OpenClub/issues/20)). Extended coaching, events, accounting, chat and integration features remain post-MVP ([#31](https://github.com/dkoller/OpenClub/issues/31)).

## Delivery gates

| Gate | Current evidence | Required next acceptance |
| --- | --- | --- |
| **Synthetic CI engineering** | Keycloak/Chromium member, team, grant and role journeys; separate Rauthy HTTPS password, passkey, MFA, email OTP, key-rotation, restore and administrator-assisted recovery acceptance; 21-route contract drift checks; disposable Compose persistence | Continue covering meaningful denied-role, private response and accessibility edges in [#24](https://github.com/dkoller/OpenClub/issues/24) / [#25](https://github.com/dkoller/OpenClub/issues/25); reconcile contract [#13](https://github.com/dkoller/OpenClub/issues/13) |
| **M1 — hosted synthetic MVP** | Reference HTTPS/OIDC overlay and read-only probes exist; CI resource samples and restart persistence are **not** external host evidence | Operator-approved reachable HTTPS/OIDC demo; witnessed synthetic end-to-end browser flow, hosted persistence/restart, real resource/cost and limitation evidence in [#27](https://github.com/dkoller/OpenClub/issues/27) / [#28](https://github.com/dkoller/OpenClub/issues/28); [M1 evidence checklist](deployment/m1-resource-evidence.md) |
| **M2 — real member data** | Privacy inventory and independent recovery runbook exist; no approval inferred | Human-approved lawful basis, minors/contact policy, private-field contract, retention/export/erasure/audit, backup/restore and witnessed operator recovery under [#12](https://github.com/dkoller/OpenClub/issues/12), [#13](https://github.com/dkoller/OpenClub/issues/13), [#26](https://github.com/dkoller/OpenClub/issues/26), [#180](https://github.com/dkoller/OpenClub/issues/180) |

**Never infer production readiness or authorization to enter real member information from green CI or a merged procedure.** Production credentials, spending, operator actions and privacy/legal approvals require separate explicit authorization.

## Where to find authoritative instructions

| Subject | Source |
| --- | --- |
| Current feature and acceptance status | [GitHub issues](https://github.com/dkoller/OpenClub/issues) |
| Technical API and unresolved contract review | [OpenAPI working draft](contracts/openapi-draft.yaml), [contract review](contracts/contract-review.md), [#13](https://github.com/dkoller/OpenClub/issues/13) |
| Single-club operations, HTTPS and resource measurements | [Deployment runbook](deployment/single-club.md), [M1 evidence checklist](deployment/m1-resource-evidence.md) |
| OIDC and operator recovery | [Rauthy evaluation](development/rauthy-local.md), [Keycloak development](development/keycloak-local.md), [admin recovery procedure](security/admin-recovery-procedure.md) |
| Contribution, PR-size limit and controlled development loop | [AGENTS.md](../AGENTS.md), [documentation maintenance](contributing/documentation.md) |

Delivery policy: no more than **5,000 added + deleted lines per PR**, with applicable CI, review and documentation changes in the same PR. The owner-authorized controlled development loop may merge after required checks pass; this does **not** authorize production release, paid services or sensitive-data use. See [AGENTS.md](../AGENTS.md).

## Historical chronology

The [2026-11-17 roadmap archive](history/roadmap-2026-11-17.md) preserves the original R01–R22 tables, dated PR summaries and earlier scope statements **as historical snapshots**. They must not override the current single-club ADR, the live issues or the acceptance gates above.
