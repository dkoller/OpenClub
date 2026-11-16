# M1 private team directory (synthetic-data only)

The team read endpoint is a narrowly scoped implementation step toward [#75](https://github.com/dkoller/OpenClub/issues/75) and [#76](https://github.com/dkoller/OpenClub/issues/76), **not** acceptance of the full team/permission contract or M2 personal-data handling.

- `GET /api/v1/teams` requires a verified browser session and returns `401` without one.
- A signed-in actor without an active club-wide or team-scoped `VIEW_TEAMS` / `MANAGE_TEAMS` grant receives `403`. Revocations take effect on the next read.
- A grant scoped to one team shows only that team. A club-wide grant allows all teams. The response is `{"items":[{"id":"<uuid>","name":"Juniors","memberCount":2}]}`; empty visible lists return `{"items":[]}`.
- Team names and member counts are **not anonymous**. The response contains no member names, birth dates, email addresses or phones and carries `Cache-Control: no-store`.
- Failures return generic JSON codes; unexpected failures use `503` and do not disclose database details.
- DE/EN/FR/IT browser states cover loading, denial, empty lists, errors and retry.

**Still open:** team creation, editing and deletion; membership assignments; grant/revoke routes; permission-management browser UI; approved full R04 contract; M1 browser acceptance and deployment; separate M2 privacy sign-off. This slice uses only synthetic test records and does not authorize real data.
