# Retention cleanup (V13; owner-approved defaults of 2026-11-18, controller decisions of 2026-11-20)

The API runs `RetentionCleanup` once an hour while a PostgreSQL database is configured. Each run works in bounded batches (100 rows, at most 10 batches) so a backlog cannot monopolize the connection pool. Values are the **owner's product decisions** ([ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md), decisions 7 and 14; the account rule was set to 2 years of inactivity by the owner); the controller's approval is still required before real member data, and the periods are constants in the code until the controller changes them.

| Data | Rule | How |
| --- | --- | --- |
| `audit_events` | Deleted when older than **12 months** | Batch chosen in a MATERIALIZED CTE so the limit holds, rows locked with `SKIP LOCKED`. |
| `accounts` | Deleted when **no member is linked** and the last login is older than **2 years**; the account's identity bindings and sessions go with it (`ON DELETE CASCADE`) | `accounts.last_login_at` (V13) is set whenever a session is created. Existing accounts start at the migration time. |
| `members` (archived) | Erased **24 months** after archiving: the row is deleted (archiving already removed grants and team assignments) and a `MEMBER_ERASED` audit event is written in the same statement | Bounded batches, `SKIP LOCKED`. Erasing earlier on request is the operator command in [single-club operations](../deployment/single-club.md#erasing-a-member-operator-only-permanent). **No legal-hold switch exists yet**: the controller must decide on one before real member data. |

| `event_attendance` | Deleted **12 months** after the event ended | Controller decision C10 of 2026-11-20 ([ADR-0008 addendum 19](../decisions/0008-owner-decisions-2026-11-18.md)). Routine expiry of club records, not audited. |
| `team_events` | Deleted **24 months** after the event ended; any remaining answers go with it (`ON DELETE CASCADE`) | C10, not audited. |
| `fee_periods` with their `member_fees` | Deleted **24 months** after the due date (cascade). The club's bookkeeping keeps what it needs for 10 years (Art. 958f OR) outside OpenClub, so export what you need first. | C10, not audited. |
| `announcements` | Deleted **6 months** after the show-until date | C10, not audited. |

All seven rules use the same pattern: a batch of at most 100 chosen in a `MATERIALIZED` CTE in date order and locked with `FOR UPDATE SKIP LOCKED`, so a row locked by a running change is left for the next run. Another batch runs while any rule filled its batch (at most 10 per run). `RetentionCleanupTest` covers each new rule just past and just inside its period.

**Linked accounts are never deleted**, however old: `members.account_id` is `ON DELETE SET NULL`, so deleting a linked account would silently unlink a member. The job therefore locks its candidates in one statement and deletes them in a second statement with a fresh snapshot that re-checks for a linked member. An account locked by a linking or recovery transaction is skipped. Tests cover both rules, the batch limit, a linked old account, an account locked by another transaction and the cascade.

**Not done by this job:** database backups, application logs, expired sessions and login transactions (`AuthRecordCleanup`, every minute). Failures are logged without data and the job tries again at the next run.
