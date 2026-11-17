# ADR-0007: Owner decisions on M1 hosting, contract freeze and initial privacy policy

- **Status:** Accepted by the repository owner on 2026-11-17. Asked one decision at a time; also recorded on the linked issues.
- **Scope:** Delivery gates and product policy. This is **not** legal approval and does not authorize real member data (M2).

## Context

The M1 code work was largely complete, but several gates needed decisions only the owner can make. Without them, the controlled development loop had started producing status-only changes. The open questions came from #28, #27, #13, #12, #17 and #29, plus the tool-specific wording in `AGENTS.md`.

## Decisions

| # | Topic | Decision | Issue |
| --- | --- | --- | --- |
| 1 | M1 acceptance gate | **Keep the hosted demo.** M1 is accepted only after an operator-run, externally reachable HTTPS/OIDC synthetic demo with witnessed evidence ([checklist](../deployment/m1-resource-evidence.md)). No re-scoping to a local demo. | [#28](https://github.com/dkoller/OpenClub/issues/28) |
| 1a | Demo login provider | **Not decided yet**: Keycloak or Rauthy, one per installation ([ADR-0006](0006-oidc-provider-choice.md)). | #28 |
| 1b | Demo hosting cost | **Up to €10/month approved** for the synthetic demo (budget ~4 GB VPS class). Anything above needs a new decision. | #28, [#27](https://github.com/dkoller/OpenClub/issues/27) |
| 1c | Demo operator | **Someone other than the owner**, not yet named. | #28 |
| 2 | API contract | **Freeze the implemented, drift-checked synthetic M1 routes as OpenAPI `1.0.0`.** Move not-implemented M2 proposals (full private-field update, HTTP reactivation, export/erasure) to a separate unversioned draft. The freeze is delivered as a PR for owner approval. | [#13](https://github.com/dkoller/OpenClub/issues/13) |
| 3 | Adult age | **Configurable per club**, default **18**, within the schema bounds 13–25. Adults must provide email and phone and are shown with full name; minors' contacts are optional and minors are shown as first name plus surname initial. | [#12](https://github.com/dkoller/OpenClub/issues/12), [#17](https://github.com/dkoller/OpenClub/issues/17) |
| 4 | Contact visibility | **Only explicitly granted roles** may read members' email and phone: holders of a member-editing permission and, for their own team only, team managers. Ordinary members see names only. | #12 |
| 5 | Tool-specific agent rules | **Generalise** the ChatGPT-specific rules in `AGENTS.md` into tool-neutral rules, keeping the ChatGPT connector limit as an observed example. | — |
| 6 | Issue #29 (AI review experiment) | **Keep as low-priority backlog** (post-M1). | [#29](https://github.com/dkoller/OpenClub/issues/29) |

## Consequences

- **M1 stays blocked** until the operator is named and the provider chosen. No code change can unblock it, so the controlled loop must not treat M1 as reachable through more code.
- **Implementation now unblocked:**
  - the `1.0.0` contract freeze (#13);
  - the per-club adult-age setting: authorized `PATCH`, 409 when existing adults would lack contacts, four-language UI and tests (#17);
  - any future contact-reading endpoint, which must enforce decision 4 server-side.
- **Still open for M2 (#12, #26, #180):** controller and lawful basis, retention, consent/notices, export/erasure/audit, backups and witnessed recovery.

## Implementation notes

- **Decision 2 implemented (2026-11-17):** `docs/contracts/openapi-v1.yaml` (version `1.0.0`, exactly the 21 implemented operations) and `docs/contracts/openapi-m2-draft.yaml` (unapproved M2 proposals) replace the former `openapi-draft.yaml`. `dev/verify-m1-openapi.py` enforces an exact operation match, the frozen version, and the NOT IMPLEMENTED markers in the M2 draft.
- **Decision 3 implemented (2026-11-17):** `PATCH /api/v1/club/adult-age-policy` (whole number 13–25; same ADMIN + MANAGE_PERMISSIONS, Origin and CSRF guards as the player-gender policy; 409 when members who would become adults lack contacts) and `adultAgeYears` in `GET /api/v1/club`. There is a four-language settings field, and the member form shows the club's threshold. Contract `1.1.0`.
