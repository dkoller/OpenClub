# M1 private team directory (synthetic-data only)

The team read endpoint is a narrowly scoped implementation step toward [#75](https://github.com/dkoller/OpenClub/issues/75) and [#76](https://github.com/dkoller/OpenClub/issues/76), **not** acceptance of the full team/permission contract or M2 personal-data handling.

- `GET /api/v1/teams` requires a verified browser session and returns `401` without one.
- A signed-in actor without an active club-wide or team-scoped `VIEW_TEAMS` / `MANAGE_TEAMS` grant receives `403`. Revocations take effect on the next read.
- A grant scoped to one team shows only that team. A club-wide grant allows all teams. The response is `{"items":[{"id":"<uuid>","name":"Juniors","memberCount":2}]}`; empty visible lists return `{"items":[]}`.
- Team names and member counts are **not anonymous**. The response contains no member names, birth dates, email addresses or phones and carries `Cache-Control: no-store`.
- Failures return generic JSON codes; unexpected failures use `503` and do not disclose database details.
- DE/EN/FR/IT browser states cover loading, denial, empty lists, errors and retry.

**Additional synthetic M1 workflow:** A verified first administrator receives explicit club-level `VIEW_TEAMS` and `MANAGE_TEAMS` grants at operator bootstrap. `POST /api/v1/teams` accepts strict bounded JSON `{"name":"Juniors"}` with exact configured Origin and session CSRF token. Only a club-wide `MANAGE_TEAMS` grant permits creation; team-scoped grants do not. The route returns `201` with the new ID and Location; missing/invalid fields return `400`, duplicates `409`, unauthenticated `401`, denied `403`, and unexpected failures `503`. The four-language browser view includes team creation and reloads the directory after a successful request.

**Still open:** team rename and deletion; membership assignments; grant/revoke routes; permission-management browser UI; approved full R04 contract; M1 browser acceptance and deployment; separate M2 privacy sign-off. This slice uses only synthetic test records and does not authorize real data.


## M1 private team roster and assignments

- `GET /api/v1/teams/{teamId}/members` requires **both** live `VIEW_MEMBER_DIRECTORY` club grant and exact team `VIEW_TEAMS` / `MANAGE_TEAMS` authorization. The response has only `id`, `firstName`, `lastName` and an optional opaque team/account-scoped `nextCursor`; limit 1–50, default 20. It carries `Cache-Control: no-store`. Unauthorized roster reads are denied without disclosing names.
- `POST /api/v1/teams/{teamId}/members/{memberId}` assigns a member and `DELETE` removes an assignment. Both require verified sessions, exact Origin, session-bound CSRF and `MANAGE_TEAMS` at club or exact-team scope. The implementation reuses the internal database-guarded assignment service, which rejects archived members.
- A successful assignment/removal returns `204`; denial `403`, missing session `401`, malformed IDs/cursors `400`, duplicates `409`, absent assignments `404`. A team manager without private directory access can operate only on IDs already known to them; no private name roster is exposed.
- The localized browser workflow searches the authorized member directory, offers assignment/removal, and refreshes team counts. It hides private roster controls when dual visibility is denied.

This is not approval of public member IDs, broader confidential fields, permission-delegation endpoints, or real-data operation.

## Team rename (synthetic M1)

`PATCH /api/v1/teams/{teamId}` accepts the same strict name-only JSON object as team creation (maximum 4 KiB). A verified session, exact configured Origin, matching CSRF token and an **active club-wide** `MANAGE_TEAMS` grant are required; a team-scoped grant cannot rename a team. A successful rename returns `204`; unauthenticated `401`, unauthorized `403`, malformed input `400`, nonexistent team `404`, duplicate team name `409`, and unavailable service `503`. The localized browser provides a rename form. Existing member assignments remain attached to the unchanged team UUID. This is a synthetic workflow only; full private/production contracts remain subject to independent approval.
