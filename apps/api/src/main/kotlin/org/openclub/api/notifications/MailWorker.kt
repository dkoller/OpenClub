package org.openclub.api.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.ActorSql
import java.util.UUID

/**
 * Sends the email outbox (OpenAPI 1.19.0, ADR-0008 addendum 23) once a minute: queues due shift reminders, helper calls and answer reminders, sends up
 * to [BATCH] messages and deletes old rows. Rows are locked with `SKIP LOCKED`, so two API instances never send the
 * same message. The address is read when sending; a member who is no longer active and verified, or has no
 * address, gets nothing. A sent message keeps only its subject; its text is cleared. A failed message is tried again after 15 and 30 minutes, at most three times. Sent rows
 * are deleted after 7 days and unsent ones after 2 days. Failures are logged without addresses or content.
 */
internal object MailWorker {
    const val BATCH = 20
    const val PARALLEL = 8
    const val MAX_FAILURES = 10

    suspend fun run(database: DatabaseRuntime, notifier: Notifier, mailer: Mailer?, pusher: PushSender? = null) = coroutineScope {
        // Push runs in its own loop, so slow push services never hold up email (security review 2026-11-26).
        if (pusher != null) launch {
            while (isActive) {
                try {
                    wakeDevices(database, pusher)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    System.err.println("Push run failed")
                }
                delay(30_000)
            }
        }
        while (isActive) {
            try {
                database.onDatabaseThread { db -> db.transaction { configuration -> notifier.queueShiftReminders(DSL.using(configuration)) } }
                database.onDatabaseThread { db -> db.transaction { configuration -> notifier.queueHelperCalls(DSL.using(configuration)) } }
                database.onDatabaseThread { db -> db.transaction { configuration -> notifier.queueAnswerReminders(DSL.using(configuration)) } }
                if (mailer != null) database.onDatabaseThread { sendBatch(it, mailer) }
                database.onDatabaseThread { cleanup(it) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                System.err.println("Email outbox run failed")
            }
            delay(60_000)
        }
    }

    data class Outcome(val sent: Int, val failed: Int, val skipped: Int)

    fun sendBatch(database: DSLContext, mailer: Mailer, limit: Int = BATCH): Outcome = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        val due = db.fetch(
            """SELECT o.id, o.subject, o.body, o.attempts,
                      CASE WHEN ${ActorSql.activeVerified("m")} THEN m.email END AS address
               FROM email_outbox o JOIN members m ON m.id = o.member_id
               WHERE o.sent_at IS NULL AND o.attempts < 3 AND o.next_attempt_at <= now()
               ORDER BY o.next_attempt_at, o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED""", limit)
        var sent = 0
        var failed = 0
        var skipped = 0
        for (row in due) {
            val id = row.get("id", UUID::class.java)
            val address = row.get("address", String::class.java)
            if (address == null || row.get("body", String::class.java).isEmpty()) {
                // The member left, lost their sign-in or their address: drop the message.
                db.execute("UPDATE email_outbox SET attempts = 3 WHERE id = ?", id)
                skipped++
                continue
            }
            try {
                mailer.send(address, row.get("subject", String::class.java), row.get("body", String::class.java))
                // Only the subject stays (for the push inbox) until the row is deleted.
                db.execute("UPDATE email_outbox SET sent_at = now(), body = '' WHERE id = ?", id)
                sent++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                val attempts = row.get("attempts", Int::class.java) + 1
                db.execute(
                    "UPDATE email_outbox SET attempts = ?, next_attempt_at = now() + make_interval(mins => ?) WHERE id = ?",
                    attempts, attempts * 15, id)
                failed++
            }
        }
        if (failed > 0) System.err.println("Email outbox: $failed message(s) failed and will be retried")
        Outcome(sent, failed, skipped)
    }

    /**
     * Push (OpenAPI 1.33.0): for messages queued in the last day and not yet pushed, wakes each recipient's devices
     * once with an empty push; the app then fetches the text from this server. Rows are marked first (`SKIP LOCKED`),
     * and the HTTP calls run outside the transaction. A subscription the push service reports gone (404, 410) is
     * deleted, and so is one that failed 10 times in a row. Failures are counted, never logged with endpoints.
     */
    suspend fun wakeDevices(database: DatabaseRuntime, pusher: PushSender, limit: Int = 200): Int {
        val endpoints = database.onDatabaseThread { db -> dueEndpoints(db, limit) }
        // At most PARALLEL calls at a time, each bounded by the sender's own timeouts.
        val statuses = coroutineScope {
            val slots = Semaphore(PARALLEL)
            endpoints.map { endpoint -> async(Dispatchers.IO) { slots.withPermit { endpoint to pusher.send(endpoint) } } }.awaitAll()
        }
        val failed = statuses.count { (_, status) -> status !in 200..299 && status != 404 && status != 410 }
        database.onDatabaseThread { db -> statuses.forEach { (endpoint, status) -> record(db, endpoint, status) } }
        if (failed > 0) System.err.println("Push: $failed device(s) could not be woken")
        return endpoints.size
    }

    /**
     * Success resets the failure count; 404/410 (gone) deletes the subscription; any other answer, or none, counts a
     * failure, and the [MAX_FAILURES]th in a row deletes it.
     */
    fun record(db: DSLContext, endpoint: String, status: Int) {
        when {
            status in 200..299 ->
                db.execute("UPDATE push_subscriptions SET last_pushed_at = now(), failures = 0 WHERE endpoint = ?", endpoint)
            status == 404 || status == 410 -> db.execute("DELETE FROM push_subscriptions WHERE endpoint = ?", endpoint)
            else -> {
                db.execute("UPDATE push_subscriptions SET failures = failures + 1 WHERE endpoint = ?", endpoint)
                db.execute("DELETE FROM push_subscriptions WHERE endpoint = ? AND failures >= ?", endpoint, MAX_FAILURES)
            }
        }
    }

    /** Marks up to [limit] queued messages as pushed and answers the endpoints of their recipients' devices. */
    fun dueEndpoints(database: DSLContext, limit: Int = 200): List<String> = database.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val members = tx.fetch(
            """WITH due AS (SELECT id FROM email_outbox
                            WHERE NOT push_done AND created_at > now() - INTERVAL '1 day'
                            ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED),
                    marked AS (UPDATE email_outbox o SET push_done = TRUE FROM due WHERE o.id = due.id RETURNING o.member_id)
               SELECT DISTINCT member_id FROM marked""", limit).map { it.get(0, UUID::class.java) }
        if (members.isEmpty()) emptyList() else tx.fetch(
            "SELECT endpoint FROM push_subscriptions WHERE member_id = ANY(?::uuid[]) ORDER BY endpoint",
            members.map(UUID::toString).toTypedArray()).map { it.get(0, String::class.java) }
    }

    /**
     * Deletes old outbox rows (answers how many) and the coach-message log rows whose event was deleted, once they no
     * longer count for the daily limit.
     */
    fun cleanup(database: DSLContext): Int {
        database.execute("DELETE FROM coach_messages WHERE event_id IS NULL AND sent_at < now() - INTERVAL '1 day'")
        return database.execute(
            """DELETE FROM email_outbox
               WHERE (sent_at IS NOT NULL AND sent_at < now() - INTERVAL '7 days')
                  OR (sent_at IS NULL AND created_at < now() - INTERVAL '2 days')""")
    }
}
