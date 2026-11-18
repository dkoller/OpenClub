# M1 operation-to-test coverage (synthetic; supports R04/#13 review)

Maps each of the 26 registered synthetic M1 operations in
[`openapi-v1.yaml`](openapi-v1.yaml) (version 1.2.0) to the tests that exercise it. The operation list and
documented status codes below were generated from that file and every test class named exists in
`apps/api/src/test/kotlin/org/openclub/api`. It is a review aid. It does **not** approve the contract,
private fields, retention or real data; those remain open in #13 and #12.

How to read the columns of test types:

- `…HttpRoutesTest`: the happy path and the main denials through the real Ktor routes on PostgreSQL.
- `…HttpInputAndEscalationTest`: malformed input, privilege escalation and "authorize before validate"
  (callers without the grant get 403, never 400) for the whole route group (#24).
- `PrivateRoutesRevocationTest`: a still-valid session whose verified identity was revoked gets 403 on every
  private route.
- `ConcurrentLastManagerHttpTest`: racing role changes and grant revocations always leave a manager.
- Service tests (`MemberCreationTest`, `CapabilityGrantsTest`, …): the same rules at the SQL service layer.

| Operation | Documented statuses | Tests |
|---|---|---|
| `GET /v1/club` | 200, 401, 403, 503 | `SingleClubRoutesTest`, `PrivateRoutesRevocationTest` |
| `PATCH /v1/club` | 204, 400, 401, 403, 503 | `SingleClubRoutesTest`, `ClubSettingsHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `PATCH /v1/club/player-gender-policy` | 204, 400, 401, 403, 409, 503 | `ClubSettingsHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `ClubPolicyChangeTest` |
| `PATCH /v1/club/adult-age-policy` | 204, 400, 401, 403, 409, 503 | `ClubSettingsHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `ClubPolicyChangeTest` |
| `GET /v1/public/members` | 200, 503 | `PublicMemberNamesTest`, `AuthAndPublicRoutesInputTest` |
| `GET /v1/members` | 200, 400, 401, 403, 503 | `MemberHttpRoutesTest`, `MemberHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `MemberDirectoryTest` |
| `POST /v1/members` | 201, 400, 401, 403, 409, 503 | `MemberHttpRoutesTest`, `MemberHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `MemberCreationTest` |
| `GET /v1/members/{memberId}` | 200, 400, 401, 403, 404, 503 | `MemberHttpRoutesTest`, `MemberHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `PATCH /v1/members/{memberId}` | 204, 400, 401, 403, 404, 503 | `MemberHttpRoutesTest`, `MemberHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `MemberDetailsUpdateTest` |
| `POST /v1/members/{memberId}/archive` | 204, 400, 401, 403, 404, 409, 503 | `MemberHttpRoutesTest`, `MemberHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `MemberArchiveTest`, `ArchivedMemberConcurrencyTest` |
| `GET /v1/members/{memberId}/role` | 200, 400, 401, 403, 503 | `MemberRoleHttpRoutesTest`, `RoleHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `PATCH /v1/members/{memberId}/role` | 204, 400, 401, 403, 503 | `MemberRoleHttpRoutesTest`, `RoleHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `ConcurrentLastManagerHttpTest`, `MemberRolesTest` |
| `GET /v1/teams` | 200, 401, 403, 503 | `TeamHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `POST /v1/teams` | 201, 400, 401, 403, 409, 503 | `TeamHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `TeamAssignmentsTest` |
| `PATCH /v1/teams/{teamId}` | 204, 400, 401, 403, 404, 409, 503 | `TeamHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `TeamAssignmentsTest` |
| `DELETE /v1/teams/{teamId}` | 204, 400, 401, 403, 404, 503 | `TeamHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `GET /v1/teams/{teamId}/members` | 200, 400, 401, 403, 503 | `TeamMemberHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `TeamMemberDirectoryTest`, `TeamScopedGrantsTest` |
| `POST /v1/teams/{teamId}/members/{memberId}` | 204, 400, 401, 403, 409, 503 | `TeamMemberHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `TeamScopedGrantsTest` |
| `DELETE /v1/teams/{teamId}/members/{memberId}` | 204, 400, 401, 403, 404, 503 | `TeamMemberHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `TeamScopedGrantsTest` |
| `GET /v1/grants` | 200, 400, 401, 403, 503 | `GrantHttpRoutesTest`, `GrantHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `POST /v1/grants` | 204, 400, 401, 403, 503 | `GrantHttpRoutesTest`, `GrantHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `CapabilityGrantsTest` |
| `POST /v1/members/{memberId}/invitation` | 201, 400, 401, 403, 404, 409, 503 | `MemberInvitationHttpTest`, `PrivateRoutesRevocationTest`, `MemberInvitationsTest` |
| `DELETE /v1/members/{memberId}/invitation` | 204, 400, 401, 403, 404, 503 | `MemberInvitationHttpTest`, `PrivateRoutesRevocationTest`, `MemberInvitationsTest` |
| `DELETE /v1/members/{memberId}/account-link` | 204, 400, 401, 403, 404, 409, 503 | `MemberInvitationHttpTest`, `PrivateRoutesRevocationTest`, `MemberInvitationsTest` |
| `POST /v1/invitations/accept` | 204, 400, 401, 429, 503 | `MemberInvitationHttpTest` (no capability: every failure is one generic 400, rate limited), `MemberInvitationsTest` |
| `DELETE /v1/grants` | 204, 400, 401, 403, 503 | `GrantHttpRoutesTest`, `GrantHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `ConcurrentLastManagerHttpTest`, `CapabilityGrantsTest` |
Browser coverage of the same operations is in `dev/auth-e2e/club-landing.spec.js` (real Keycloak and
Chromium, synthetic data): member, team, grant, role and club-setting workflows in four languages, real
database denial with grants removed, a team-scoped grant that shows one team, invalid-input handling and
accessibility structure (#25). The anonymous and authentication routes outside the 26 operations are covered
by `AuthAndPublicRoutesInputTest`, `SessionRoutesTest` and `BrowserLoginRoutesTest`.

## Known gaps in this evidence

- **Route-level 503.** The mapping from database faults to 503 is tested (`DatabaseErrorsTest`) and each
  route's code path is reviewed, but no test makes a real route answer 503 for every operation. A realistic
  fault injection is not built.
- **Runtime payload conformance.** Response bodies are asserted exactly only for member create, search,
  detail, the anonymous directory and the error envelope (#306). Other responses are checked for status and
  for absence of private fields, not validated against the OpenAPI schema. Adding a schema validator would add a
  dependency and needs an owner decision.
- **Statuses never produced by a test.** Where a documented status is only reachable through a race or a
  fault (for example some 409 and 503 cases), it is covered by service tests or reasoning, not by an HTTP test.
- **Not tested at all:** any proposed, unimplemented operation, including member reactivation over HTTP, which the
  [M2 draft](openapi-m2-draft.yaml) keeps separate.
