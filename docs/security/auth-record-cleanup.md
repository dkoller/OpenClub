# R05: Expired authentication record cleanup

With OIDC enabled, the API runs a coroutine cleanup loop immediately and then
once per minute. It deletes expired login transactions and expired sessions
from PostgreSQL in 100-row batches, at most ten batches per cycle. Each
statement uses `FOR UPDATE SKIP LOCKED` to avoid blocking concurrent
authentication work and reuses existing expiry indexes. Session cleanup filters on `idle_expires_at` alone so the existing
`auth_sessions_expiration_idx` supports an indexed range scan. The schema
constraint `idle_expires_at <= absolute_expires_at` guarantees that sessions
past their absolute deadline also satisfy this predicate.

The coroutine uses the existing bounded database dispatcher and is cancelled
and joined before the DB pool closes. Failures are reported without SQL
parameters or authentication secrets, and the next cycle retries. An outage
does not make expired records valid: authentication reads already enforce
expiry independently. Cleanup is best-effort and eventual.

No new scheduler dependency, schema migration, environment variable, or
change to cookie/session semantics. Run PostgreSQL-backed
`./gradlew test --tests '*AuthRecordCleanupTest'` in `apps/api`.
