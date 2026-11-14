# R05: Expired authentication record cleanup

With OIDC enabled, the API runs a coroutine cleanup loop immediately and then
once per minute. It deletes expired login transactions and expired sessions
from PostgreSQL in 100-row batches, at most ten batches per cycle. Each
statement uses `FOR UPDATE SKIP LOCKED` to avoid blocking concurrent
authentication work and reuses existing expiry indexes. Sessions are
expired when either their idle or absolute deadline has passed.

The coroutine uses the existing bounded database dispatcher and is cancelled
and joined before the DB pool closes. Failures are reported without SQL
parameters or authentication secrets, and the next cycle retries. An outage
does not make expired records valid: authentication reads already enforce
expiry independently. Cleanup is best-effort and eventual.

No new scheduler dependency, schema migration, environment variable, or
change to cookie/session semantics. Run PostgreSQL-backed
`./gradlew test --tests '*AuthRecordCleanupTest'` in `apps/api`.
