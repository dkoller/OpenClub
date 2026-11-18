# Retention cleanup (V13; owner-approved defaults of 2026-11-18)

The API runs `RetentionCleanup` once an hour while a PostgreSQL database is configured. Each run works in bounded batches (100 rows, at most 10 batches) so a backlog cannot monopolize the connection pool. Values are the **product defaults approved by the owner** ([ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md), decision 7); the controller's approval is still required before real member data, and the periods are constants in the code until the controller changes them.

| Data | Rule | How |
| --- | --- | --- |
| `audit_events` | Deleted when older than **12 months** | Batch chosen in a MATERIALIZED CTE so the limit holds, rows locked with `SKIP LOCKED`. |
| `accounts` | Deleted when **no member is linked** and the last login is older than **90 days**; the account's identity bindings and sessions go with it (`ON DELETE CASCADE`) | `accounts.last_login_at` (V13) is set whenever a session is created. Existing accounts start at the migration time. |

**Linked accounts are never deleted**, however old: `members.account_id` is `ON DELETE SET NULL`, so deleting a linked account would silently unlink a member. The job therefore locks its candidates in one statement and deletes them in a second statement with a fresh snapshot that re-checks for a linked member. An account locked by a linking or recovery transaction is skipped. Tests cover both rules, the batch limit, a linked old account, an account locked by another transaction and the cascade.

**Not done by this job:** erasing archived members (a separate explicit operation, planned), database backups, application logs, expired sessions and login transactions (`AuthRecordCleanup`, every minute). Failures are logged without data and the job tries again at the next run.
