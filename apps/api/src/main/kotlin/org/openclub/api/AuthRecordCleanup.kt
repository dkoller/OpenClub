package org.openclub.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jooq.DSLContext

/** Remove expired records in bounded PostgreSQL batches without touching live records. */
internal object AuthRecordCleanup {
    internal data class Counts(val logins: Int, val sessions: Int)

    fun pruneBatch(db: DSLContext, batchSize: Int = 100): Counts {
        require(batchSize in 1..1000)
        val logins = db.execute(
            """DELETE FROM oidc_login_transactions WHERE state_hash IN (
                SELECT state_hash FROM oidc_login_transactions
                WHERE expires_at <= CURRENT_TIMESTAMP ORDER BY expires_at
                LIMIT ? FOR UPDATE SKIP LOCKED
            )""",
            batchSize,
        )
        val sessions = db.execute(
            """DELETE FROM auth_sessions WHERE session_hash IN (
                SELECT session_hash FROM auth_sessions
                WHERE idle_expires_at <= CURRENT_TIMESTAMP OR absolute_expires_at <= CURRENT_TIMESTAMP
                ORDER BY idle_expires_at LIMIT ? FOR UPDATE SKIP LOCKED
            )""",
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
            } catch (_: Exception) {
                // Avoid logging potentially sensitive SQL parameters or session material.
                System.err.println("Expired authentication record cleanup failed")
            }
            delay(60_000)
        }
    }
}
