package org.openclub.api.teams

import org.jooq.DSLContext
import java.util.UUID

/**
 * Internal-only team roster. Both directory visibility and exact-team viewing
 * are required before any member name is selected. No HTTP route or PII export.
 */
internal class TeamMemberDirectory(private val database: DSLContext) {
    data class Summary(val id: UUID, val firstName: String, val lastName: String)
    data class After(val lastName: String, val firstName: String, val id: UUID)
    data class Page(val members: List<Summary>, val next: After?)

    fun list(accountId: UUID, teamId: UUID, limit: Int = 20, after: After? = null): Page {
        require(limit in 1..50) { "Page size must be between 1 and 50" }
        val cursorSql = if (after == null) "" else
            "AND (target.last_name, target.first_name, target.id) > (?, ?, ?)"
        val args = mutableListOf<Any>(teamId, accountId)
        if (after != null) args.addAll(listOf(after.lastName, after.firstName, after.id))
        args.add(limit + 1)

        val rows = database.fetch(
            """SELECT target.id, target.first_name, target.last_name
               FROM member_team_assignments a
               JOIN members target ON target.id = a.member_id
               WHERE a.team_id = ? AND target.archived_at IS NULL
                 AND EXISTS (
                   SELECT 1 FROM members actor
                   JOIN club_capability_grants directory ON directory.member_id = actor.id
                     AND directory.capability = 'VIEW_MEMBER_DIRECTORY'
                   WHERE actor.account_id = ? AND actor.archived_at IS NULL
                     AND (
                       EXISTS (SELECT 1 FROM club_capability_grants g
                               WHERE g.member_id = actor.id
                                 AND g.capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS'))
                       OR EXISTS (SELECT 1 FROM team_capability_grants g
                                  WHERE g.member_id = actor.id AND g.team_id = a.team_id
                                    AND g.capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS'))
                     )
                 )
               $cursorSql
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
