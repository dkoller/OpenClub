package org.openclub.api.auth

import org.openclub.api.database.*
import org.openclub.api.members.*

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jooq.DSLContext
import org.openclub.api.logFailure

/** Remove expired records in bounded PostgreSQL batches without touching live records. */
internal object AuthRecordCleanup {
    internal data class Counts(val logins: Int, val sessions: Int)

    /**
     * The batch is selected in a MATERIALIZED CTE so it is evaluated exactly once. As a plain
     * `IN (SELECT ... LIMIT ? FOR UPDATE SKIP LOCKED)` the planner may re-run the subquery per
     * target row (nested-loop semi join), and rows this statement already deleted are then
     * skipped, so each re-run selects the next expired row and the limit is not honored.
     */
    fun pruneBatch(db: DSLContext, batchSize: Int = 100): Counts {
        require(batchSize in 1..1000)
        val logins = db.execute(
            """WITH batch AS MATERIALIZED (
                SELECT state_hash FROM oidc_login_transactions
                WHERE expires_at <= CURRENT_TIMESTAMP ORDER BY expires_at
                LIMIT ? FOR UPDATE SKIP LOCKED
            )
            DELETE FROM oidc_login_transactions WHERE state_hash IN (SELECT state_hash FROM batch)""",
            batchSize,
        )
        val sessions = db.execute(
            """WITH batch AS MATERIALIZED (
                SELECT session_hash FROM auth_sessions
                WHERE idle_expires_at <= CURRENT_TIMESTAMP
                ORDER BY idle_expires_at LIMIT ? FOR UPDATE SKIP LOCKED
            )
            DELETE FROM auth_sessions WHERE session_hash IN (SELECT session_hash FROM batch)""",
            batchSize,
        )
        return Counts(logins, sessions)
    }

    /** Start only when OIDC auth is enabled; cancellation precedes DB shutdown. */
    suspend fun run(database: DatabaseRuntime) {
        while (currentCoroutineContext().isActive) {
            try {
                // Cap work per minute so a large backlog cannot monopolize the DB pool.
                for (batch in 1..10) {
                    val counts = database.onDatabaseThread { pruneBatch(it) }
                    if (counts.logins < 100 && counts.sessions < 100) break
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // Only classes, SQL state and frames: no SQL parameters or session material (HttpAnswers.kt).
                logFailure("Expired authentication record cleanup", failure)
            }
            delay(60_000)
        }
    }
}
