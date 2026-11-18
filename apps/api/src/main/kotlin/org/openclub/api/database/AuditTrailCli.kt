package org.openclub.api.database

import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.openclub.api.club.canonicalUuid
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Read-only operator command: prints the audit trail (newest first) as tab-separated lines. The trail
 * holds identifiers only, never names or contacts, so the output is safe to attach to a recovery or
 * review case. Nothing here writes to the database.
 */
fun main() {
    val env = System.getenv()
    val filter = auditFilter(env)
    requireNotNull(DatabaseRuntime.openIfConfigured(env)) { "DB_URL required" }.use { database ->
        val rows = runBlocking { database.onDatabaseThread { AuditTrail.read(it, filter) } }
        print(AuditTrail.format(rows))
    }
}

/**
 * Validates the operator environment before any database connection. All filters are optional:
 * OPENCLUB_AUDIT_SINCE (an ISO instant or a date, UTC), OPENCLUB_AUDIT_MEMBER (a canonical member UUID)
 * and OPENCLUB_AUDIT_LIMIT (1 to 5000, default 200).
 */
internal fun auditFilter(env: Map<String, String>): AuditTrail.Filter {
    val since = env["OPENCLUB_AUDIT_SINCE"]?.takeIf(String::isNotBlank)?.let {
        try {
            Instant.parse(it.trim())
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(it.trim()).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("OPENCLUB_AUDIT_SINCE must be an ISO instant or date")
            }
        }
    }
    val member = env["OPENCLUB_AUDIT_MEMBER"]?.takeIf(String::isNotBlank)
        ?.let { canonicalUuid(it.trim(), "OPENCLUB_AUDIT_MEMBER") }
    val limit = env["OPENCLUB_AUDIT_LIMIT"]?.takeIf(String::isNotBlank)?.let {
        requireNotNull(it.trim().toIntOrNull()) { "OPENCLUB_AUDIT_LIMIT must be a whole number" }
    } ?: 200
    require(limit in 1..5000) { "OPENCLUB_AUDIT_LIMIT must be between 1 and 5000" }
    return AuditTrail.Filter(since, member, limit)
}

internal object AuditTrail {
    data class Filter(val since: Instant?, val member: UUID?, val limit: Int)

    data class Row(
        val id: Long, val occurredAt: Instant, val type: String, val actor: UUID?, val target: UUID?,
        val capability: String?, val scope: String?, val team: UUID?, val role: String?,
    )

    fun read(db: DSLContext, filter: Filter): List<Row> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()
        filter.since?.let { conditions += "occurred_at >= ?::timestamptz"; values += it.toString() }
        filter.member?.let { conditions += "target_member_id = ?"; values += it }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        return db.fetch(
            """SELECT id, occurred_at, event_type, actor_account_id, target_member_id, capability, scope, team_id, new_role
               FROM audit_events $where ORDER BY id DESC LIMIT ?""",
            *values.toTypedArray(), filter.limit,
        ).map {
            Row(it.get("id", Long::class.java), it.get("occurred_at", OffsetDateTime::class.java).toInstant(),
                it.get("event_type", String::class.java), it.get("actor_account_id", UUID::class.java),
                it.get("target_member_id", UUID::class.java), it.get("capability", String::class.java),
                it.get("scope", String::class.java), it.get("team_id", UUID::class.java),
                it.get("new_role", String::class.java))
        }
    }

    /** Header plus one tab-separated line per event; an empty cell is shown as "-". */
    fun format(rows: List<Row>): String = buildString {
        appendLine(listOf("id", "occurred_at_utc", "event", "actor_account", "target_member", "capability", "scope", "team", "new_role")
            .joinToString("\t"))
        for (r in rows) {
            appendLine(listOf<Any?>(r.id, r.occurredAt, r.type, r.actor, r.target, r.capability, r.scope, r.team, r.role)
                .joinToString("\t") { it?.toString() ?: "-" })
        }
    }
}
