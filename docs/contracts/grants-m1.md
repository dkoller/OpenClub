# M1 club/team capability management (synthetic-data only)

An authenticated, *verified* ADMIN with a live club-wide `MANAGE_PERMISSIONS` grant can inspect, delegate and revoke narrowly defined capabilities. This does not create a general role editor or authorize production personal data.

- `GET /api/v1/grants?memberId={uuid}` returns `{"items":[{"scope":"CLUB","capability":"VIEW_TEAMS"},{"scope":"TEAM","capability":"MANAGE_TEAMS","teamId":"{uuid}"}]}`. It requires verified ADMIN permission management and never returns names, contact fields, birth dates, login handles or session details.
- `POST /api/v1/grants` and `DELETE /api/v1/grants` accept strict JSON with required `memberId`, `capability`, `scope`, and `teamId` *only* for `TEAM`. Max 4 KiB, exact browser Origin and session-bound CSRF. Success, including idempotent no-op, returns `204`.
- Club capabilities: `VIEW_MEMBER_DIRECTORY`, `EDIT_MEMBERS`, `VIEW_TEAMS`, `MANAGE_TEAMS`, `MANAGE_PERMISSIONS`. Team scope allows only `VIEW_TEAMS` and `MANAGE_TEAMS`. The operator-side service forbids self-mutation, delegation to archived recipients, and `MANAGE_PERMISSIONS` to unverified/non-ADMIN recipients; serializes mutations and preserves a recoverable last permission manager.
- Failed authentication `401`; insufficient rights/unsafe mutations `403`; invalid type, scope, JSON or a missing/malformed `memberId` query `400`; unexpected failures `503`. Responses use `Cache-Control: no-store`.
- DE/EN/FR/IT browser UI searches authorized members, reads current grants, and supports scoped grant/revoke. Member identities must already be registered; this endpoint creates no accounts, teams or users.

Open for M2: approved permission/access matrix for real data, independent security review and operator recovery approval, retention/erasure/audit, production TLS/secrets/backups. The grant routes are part of the frozen synthetic M1 contract ([`openapi-v1.yaml`](openapi-v1.yaml), 1.1.0; [ADR-0007](../decisions/0007-owner-decisions-2026-11-17.md)); real-data and private-field contract work remains M2-only.
