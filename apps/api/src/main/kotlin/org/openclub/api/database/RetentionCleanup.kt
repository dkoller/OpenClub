package org.openclub.api.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * Retention rules approved by the owner on 2026-11-18 (ADR-0008, decisions 7 and 14), applied in bounded
 * batches: audit events are deleted after 12 months, and an account that is not linked to any member and
 * has not signed in for 2 years is deleted together with its identity bindings and sessions, and an archived
 * member is erased 24 months after archiving. Erasing earlier on request is [MemberErasure].
 *
 * Since the controller's decision of 2026-11-20 (ADR-0008 addendum 19, decision C10) also: attendance answers 12 months
 * after the event, events 24 months after they end, fee periods with all their fees 24 months after the due date (the
 * club's own bookkeeping keeps what it needs for 10 years, outside OpenClub), and announcements 6 months after their
 * show-until date. These deletions are routine expiry of club records, not decisions about one member, and are not
 * audited.
 */
internal object RetentionCleanup {
    internal data class Counts(
        val auditEvents: Int, val orphanAccounts: Int, val erasedMembers: Int,
        val attendance: Int = 0, val events: Int = 0, val feePeriods: Int = 0, val announcements: Int = 0,
        val volunteers: Int = 0, val absences: Int = 0, val rides: Int = 0, val joinRequests: Int = 0,
    ) {
        /** True when any rule filled its batch, so another batch may find more. */
        fun full(batchSize: Int) = listOf(auditEvents, orphanAccounts, erasedMembers, attendance, events, feePeriods, announcements, volunteers, absences, rides, joinRequests)
            .any { it >= batchSize }
    }

    fun pruneBatch(db: DSLContext, batchSize: Int = 100): Counts {
        require(batchSize in 1..1000)
        return Counts(pruneAuditEvents(db, batchSize), pruneOrphanAccounts(db, batchSize), eraseArchivedMembers(db, batchSize),
            pruneAttendance(db, batchSize), pruneEvents(db, batchSize), pruneFeePeriods(db, batchSize),
            pruneAnnouncements(db, batchSize), pruneVolunteers(db, batchSize), pruneAbsences(db, batchSize), pruneRides(db, batchSize), pruneJoinRequests(db, batchSize))
    }

    /** Attendance answers 12 months after the event ended. */
    private fun pruneAttendance(db: DSLContext, batchSize: Int): Int = db.execute(
        """WITH batch AS MATERIALIZED (
            SELECT ea.event_id, ea.member_id FROM event_attendance ea JOIN team_events e ON e.id = ea.event_id
            WHERE e.ends_at < CURRENT_TIMESTAMP - INTERVAL '12 months'
            ORDER BY e.ends_at LIMIT ? FOR UPDATE OF ea SKIP LOCKED
        )
        DELETE FROM event_attendance ea USING batch b WHERE ea.event_id = b.event_id AND ea.member_id = b.member_id""",
        batchSize,
    )

    /** Events 24 months after they ended (any remaining answers go with them). */
    private fun pruneEvents(db: DSLContext, batchSize: Int): Int = db.execute(
        """WITH batch AS MATERIALIZED (
            SELECT id FROM team_events WHERE ends_at < CURRENT_TIMESTAMP - INTERVAL '24 months'
            ORDER BY ends_at LIMIT ? FOR UPDATE SKIP LOCKED
        )
        DELETE FROM team_events WHERE id IN (SELECT id FROM batch)""",
        batchSize,
    )

    /** Fee periods, with every member's fee in them, 24 months after the due date. */
    private fun pruneFeePeriods(db: DSLContext, batchSize: Int): Int = db.execute(
        """WITH batch AS MATERIALIZED (
            SELECT id FROM fee_periods WHERE due_date < CURRENT_DATE - INTERVAL '24 months'
            ORDER BY due_date LIMIT ? FOR UPDATE SKIP LOCKED
        )
        DELETE FROM fee_periods WHERE id IN (SELECT id FROM batch)""",
        batchSize,
    )

    /** Volunteer sign-ups 12 months after the shift ended, like attendance answers (ADR-0008 addendum 20). */
    private fun pruneVolunteers(db: DSLContext, batchSize: Int): Int = db.execute(
        """WITH batch AS MATERIALIZED (
            SELECT v.shift_id, v.member_id FROM shift_volunteers v JOIN event_shifts s ON s.id = v.shift_id
            WHERE s.ends_at < CURRENT_TIMESTAMP - INTERVAL '12 months'
            ORDER BY s.ends_at LIMIT ? FOR UPDATE OF v SKIP LOCKED
        )
        DELETE FROM shift_volunteers v USING batch b WHERE v.shift_id = b.shift_id AND v.member_id = b.member_id""",
        batchSize,
    )

    /** Absences 12 months after their last day, like attendance answers (ADR-0008 addendum 24). */
    private fun pruneAbsences(db: DSLContext, batchSize: Int): Int = db.execute(
        """WITH batch AS MATERIALIZED (
            SELECT id FROM member_absences WHERE to_date < CURRENT_DATE - INTERVAL '12 months'
            ORDER BY to_date LIMIT ? FOR UPDATE SKIP LOCKED
        )
        DELETE FROM member_absences WHERE id IN (SELECT id FROM batch)""",
        batchSize,
    )

    /** Rides with their bookings 12 months after the event ended (ADR-0008 addendum 25). */
    private fun pruneRides(db: DSLContext, batchSize: Int): Int = db.execute(
        """WITH batch AS MATERIALIZED (
            SELECT r.id FROM event_rides r JOIN team_events e ON e.id = r.event_id
            WHERE e.ends_at < CURRENT_TIMESTAMP - INTERVAL '12 months'
            ORDER BY e.ends_at LIMIT ? FOR UPDATE OF r SKIP LOCKED
        )
        DELETE FROM event_rides WHERE id IN (SELECT id FROM batch)""",
        batchSize,
    )

    /** Open join requests 60 days after they arrived (ADR-0008 addendum 26). */
    private fun pruneJoinRequests(db: DSLContext, batchSize: Int): Int = db.execute(
        """WITH batch AS MATERIALIZED (
            SELECT id FROM join_requests WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '60 days'
            ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED
        )
        DELETE FROM join_requests WHERE id IN (SELECT id FROM batch)""",
        batchSize,
    )

    /** Announcements 6 months after their show-until date. */
    private fun pruneAnnouncements(db: DSLContext, batchSize: Int): Int = db.execute(
        """WITH batch AS MATERIALIZED (
            SELECT id FROM announcements WHERE show_until < CURRENT_DATE - INTERVAL '6 months'
            ORDER BY show_until LIMIT ? FOR UPDATE SKIP LOCKED
        )
        DELETE FROM announcements WHERE id IN (SELECT id FROM batch)""",
        batchSize,
    )

    /**
     * Archived members are erased 24 months after archiving (owner-approved default). Deleting the row is the
     * whole erasure: archiving already removed grants and assignments, and the `MEMBER_ERASED` event is written
     * in the same statement, so an event exists exactly when a member was deleted. There is no legal-hold
     * switch yet; the controller must decide on one before real member data.
     */
    private fun eraseArchivedMembers(db: DSLContext, batchSize: Int): Int = db.execute(
        """WITH batch AS MATERIALIZED (
            SELECT id FROM members
            WHERE archived_at < CURRENT_TIMESTAMP - INTERVAL '24 months'
            ORDER BY archived_at LIMIT ? FOR UPDATE SKIP LOCKED
        ), deleted AS (
            DELETE FROM members WHERE id IN (SELECT id FROM batch) RETURNING id
        )
        INSERT INTO audit_events (event_type, target_member_id) SELECT 'MEMBER_ERASED', id FROM deleted""",
        batchSize,
    )

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
               WHERE a.last_login_at < CURRENT_TIMESTAMP - INTERVAL '2 years'
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
                    if (!counts.full(100)) break
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
