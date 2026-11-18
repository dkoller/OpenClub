package org.openclub.api.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * Retention rules approved as product defaults on 2026-11-18 (ADR-0008, decision 7), applied in bounded
 * batches: audit events are deleted after 12 months, and an account that is not linked to any member and
 * has not signed in for 90 days is deleted together with its identity bindings and sessions.
 * Archived-member erasure is a separate, explicit operation and is not done here.
 */
internal object RetentionCleanup {
    internal data class Counts(val auditEvents: Int, val orphanAccounts: Int)

    fun pruneBatch(db: DSLContext, batchSize: Int = 100): Counts {
        require(batchSize in 1..1000)
        return Counts(pruneAuditEvents(db, batchSize), pruneOrphanAccounts(db, batchSize))
    }

    /** MATERIALIZED so the batch is chosen once and the limit holds (see AuthRecordCleanup). */
    private fun pruneAuditEvents(db: DSLContext, batchSize: Int): Int = db.execute(
        """WITH batch AS MATERIALIZED (
            SELECT id FROM audit_events
            WHERE occurred_at < CURRENT_TIMESTAMP - INTERVAL '12 months'
            ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
        )
        DELETE FROM audit_events WHERE id IN (SELECT id FROM batch)""",
        batchSize,
    )

    /**
     * Two statements in one transaction. The first locks the candidates, so a concurrent linking or
     * recovery that needs the account row waits (or the account is skipped). The second runs with a fresh
     * snapshot and re-checks that no member is linked: `members.account_id` is `ON DELETE SET NULL`, so a
     * stale check could otherwise silently unlink a member whose link committed after the first statement.
     */
    private fun pruneOrphanAccounts(db: DSLContext, batchSize: Int): Int = db.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val candidates = tx.fetch(
            """SELECT a.id FROM accounts a
               WHERE a.last_login_at < CURRENT_TIMESTAMP - INTERVAL '90 days'
                 AND NOT EXISTS (SELECT 1 FROM members m WHERE m.account_id = a.id)
               ORDER BY a.last_login_at LIMIT ? FOR UPDATE OF a SKIP LOCKED""",
            batchSize,
        ).map { it.get(0, UUID::class.java) }
        if (candidates.isEmpty()) return@transactionResult 0
        tx.execute(
            """DELETE FROM accounts a WHERE a.id = ANY(?::uuid[])
               AND NOT EXISTS (SELECT 1 FROM members m WHERE m.account_id = a.id)""",
            candidates.toTypedArray(),
        )
    }

    /** Hourly; cancellation precedes database shutdown. Failures are logged without data. */
    suspend fun run(database: DatabaseRuntime) {
        while (currentCoroutineContext().isActive) {
            try {
                for (batch in 1..10) {
                    val counts = database.onDatabaseThread { pruneBatch(it) }
                    if (counts.auditEvents < 100 && counts.orphanAccounts < 100) break
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                System.err.println("Retention cleanup failed")
            }
            delay(3_600_000)
        }
    }
}
