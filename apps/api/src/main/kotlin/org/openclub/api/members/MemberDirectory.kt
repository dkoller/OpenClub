package org.openclub.api.members

import org.jooq.DSLContext
import java.util.UUID

/**
 * Internal-only member directory query. Not exposed over HTTP until privacy
 * and capability rules (#12/#76) approve directory visibility.
 *
 * The installation-local actor's verified identity and explicit directory-read grant are checked
 * in the same SQL statement as the read; linking an account alone is insufficient.
 */
internal class MemberDirectory(private val database: DSLContext) {
    data class Summary(val id: UUID, val firstName: String, val lastName: String)
    data class After(val lastName: String, val firstName: String, val id: UUID)
    data class Page(val members: List<Summary>, val next: After?)

    fun search(accountId: UUID, query: String = "", limit: Int = 20, after: After? = null): Page {
        val name = query.trim()
        require(name.length <= 100) { "Search must be 100 characters or fewer" }
        require(limit in 1..50) { "Page size must be between 1 and 50" }

        val afterSql = if (after == null) "" else
            "AND (target.last_name, target.first_name, target.id) > (?, ?, ?)"
        val args = mutableListOf<Any>(accountId, name, name, name)
        if (after != null) args.addAll(listOf(after.lastName, after.firstName, after.id))
        args.add(limit + 1)

        val rows = database.fetch(
            """SELECT target.id, target.first_name, target.last_name
               FROM members target
               JOIN members actor ON actor.account_id = ? AND ${ActorSql.activeVerified("actor")}
               JOIN club_capability_grants g ON g.member_id = actor.id
                   AND g.capability = 'VIEW_MEMBER_DIRECTORY'
               JOIN clubs club ON TRUE
               WHERE target.archived_at IS NULL AND (? = '' OR POSITION(LOWER(?) IN LOWER(target.first_name || ' ' || target.last_name)) > 0
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
            )
        }
        val members = rows.take(limit)
        val next = members.lastOrNull()?.takeIf { rows.size > limit }?.let {
            After(it.lastName, it.firstName, it.id)
        }
        return Page(members, next)
    }
}
