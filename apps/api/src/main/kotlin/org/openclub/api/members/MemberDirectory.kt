package org.openclub.api.members

import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Member name search behind `GET /api/v1/members` (active members, VIEW_MEMBER_DIRECTORY) and
 * `GET /api/v1/members/archived` (archived members, EDIT_MEMBERS; OpenAPI 1.5.0, owner-approved 2026-11-19).
 *
 * The installation-local actor's verified identity and the explicit grant are checked in the same SQL
 * statement as the read; linking an account alone is insufficient. Only names (and, for archived members,
 * the archive time) are returned; contacts and birth dates stay behind the restricted detail read.
 */
internal class MemberDirectory(private val database: DSLContext) {
    data class Summary(val id: UUID, val firstName: String, val lastName: String, val archivedAt: OffsetDateTime? = null)
    data class After(val lastName: String, val firstName: String, val id: UUID)
    data class Page(val members: List<Summary>, val next: After?)

    fun search(accountId: UUID, query: String = "", limit: Int = 20, after: After? = null): Page =
        page(accountId, query, limit, after, archived = false)

    /** Archived members only, for editors (reactivation, export, erasure). */
    fun searchArchived(accountId: UUID, query: String = "", limit: Int = 20, after: After? = null): Page =
        page(accountId, query, limit, after, archived = true)

    private fun page(accountId: UUID, query: String, limit: Int, after: After?, archived: Boolean): Page {
        val name = query.trim()
        require(name.length <= 100) { "Search must be 100 characters or fewer" }
        require(limit in 1..50) { "Page size must be between 1 and 50" }

        val afterSql = if (after == null) "" else
            "AND (target.last_name, target.first_name, target.id) > (?, ?, ?)"
        val args = mutableListOf<Any>(accountId, name, name, name)
        if (after != null) args.addAll(listOf(after.lastName, after.firstName, after.id))
        args.add(limit + 1)

        val rows = database.fetch(
            """SELECT target.id, target.first_name, target.last_name, target.archived_at
               FROM members target
               JOIN members actor ON actor.account_id = ? AND ${ActorSql.activeVerified("actor")}
               JOIN club_capability_grants g ON g.member_id = actor.id
                   AND g.capability = '${if (archived) "EDIT_MEMBERS" else "VIEW_MEMBER_DIRECTORY"}'
               JOIN clubs club ON TRUE
               WHERE target.archived_at IS ${if (archived) "NOT NULL" else "NULL"} AND (? = '' OR POSITION(LOWER(?) IN LOWER(target.first_name || ' ' || target.last_name)) > 0
                   OR POSITION(LOWER(?) IN LOWER(target.last_name || ' ' || target.first_name)) > 0)
               $afterSql
               ORDER BY target.last_name, target.first_name, target.id
               LIMIT ?""",
            *args.toTypedArray(),
        ).map {
            Summary(
                requireNotNull(it.get("id", UUID::class.java)),
                requireNotNull(it.get("first_name", String::class.java)),
                requireNotNull(it.get("last_name", String::class.java)),
                it.get("archived_at", OffsetDateTime::class.java),
            )
        }
        val members = rows.take(limit)
        val next = members.lastOrNull()?.takeIf { rows.size > limit }?.let {
            After(it.lastName, it.firstName, it.id)
        }
        return Page(members, next)
    }
}
