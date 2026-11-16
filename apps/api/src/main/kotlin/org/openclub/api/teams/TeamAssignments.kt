package org.openclub.api.teams

import org.jooq.DSLContext
import java.util.UUID

/**
 * Internal team operations. actorAccountId must come from a verified session.
 * No HTTP route exists. Team grants allow scoped assignment mutations and
 * visibility; team creation/rename/deletion remain strictly club-wide.
 */
internal class TeamAssignments(private val database: DSLContext) {
    data class Team(val id: UUID, val name: String, val memberCount: Int)

    fun create(actorAccountId: UUID, name: String): UUID? {
        val normalized = validName(name)
        val id = UUID.randomUUID()
        return if (database.execute(
            """INSERT INTO teams (id, name)
               SELECT ?, ? WHERE EXISTS (
                   SELECT 1 FROM members actor
                   JOIN club_capability_grants grants ON grants.member_id = actor.id
                   JOIN clubs club ON TRUE
                   WHERE actor.account_id = ? AND actor.archived_at IS NULL
                     AND grants.capability = 'MANAGE_TEAMS'
               )""",
            id, normalized, actorAccountId,
        ) == 1) id else null
    }

    fun rename(actorAccountId: UUID, teamId: UUID, name: String): Boolean =
        database.execute(
            """UPDATE teams SET name = ?
               WHERE id = ? AND EXISTS (
                   SELECT 1 FROM members actor
                   JOIN club_capability_grants grants ON grants.member_id = actor.id
                   JOIN clubs club ON TRUE
                   WHERE actor.account_id = ? AND actor.archived_at IS NULL
                     AND grants.capability = 'MANAGE_TEAMS'
               )""",
            validName(name), teamId, actorAccountId,
        ) == 1

    fun remove(actorAccountId: UUID, teamId: UUID): Boolean =
        database.execute(
            """DELETE FROM teams WHERE id = ? AND EXISTS (
                   SELECT 1 FROM members actor
                   JOIN club_capability_grants grants ON grants.member_id = actor.id
                   JOIN clubs club ON TRUE
                   WHERE actor.account_id = ? AND actor.archived_at IS NULL
                     AND grants.capability = 'MANAGE_TEAMS'
               )""",
            teamId, actorAccountId,
        ) == 1

    fun assign(actorAccountId: UUID, teamId: UUID, memberId: UUID): Boolean =
        database.execute(
            """INSERT INTO member_team_assignments (team_id, member_id)
               SELECT team.id, member.id FROM teams team
               JOIN members member ON member.id = ? AND member.archived_at IS NULL
               WHERE team.id = ? AND EXISTS (
                   SELECT 1 FROM members actor
                   WHERE actor.account_id = ? AND actor.archived_at IS NULL AND (
                       EXISTS (SELECT 1 FROM club_capability_grants g
                               WHERE g.member_id = actor.id AND g.capability = 'MANAGE_TEAMS')
                       OR EXISTS (SELECT 1 FROM team_capability_grants g
                                  WHERE g.member_id = actor.id AND g.team_id = ?
                                    AND g.capability = 'MANAGE_TEAMS')
                   )
               ) FOR UPDATE OF member ON CONFLICT DO NOTHING""",
            memberId, teamId, actorAccountId, teamId,
        ) == 1

    fun unassign(actorAccountId: UUID, teamId: UUID, memberId: UUID): Boolean =
        database.execute(
            """DELETE FROM member_team_assignments
               WHERE team_id = ? AND member_id = ? AND EXISTS (
                   SELECT 1 FROM members actor
                   WHERE actor.account_id = ? AND actor.archived_at IS NULL AND (
                       EXISTS (SELECT 1 FROM club_capability_grants g
                               WHERE g.member_id = actor.id AND g.capability = 'MANAGE_TEAMS')
                       OR EXISTS (SELECT 1 FROM team_capability_grants g
                                  WHERE g.member_id = actor.id AND g.team_id = ?
                                    AND g.capability = 'MANAGE_TEAMS')
                   )
               )""",
            teamId, memberId, actorAccountId, teamId,
        ) == 1

    fun list(actorAccountId: UUID): List<Team> =
        database.fetch(
            """SELECT team.id, team.name, COUNT(assignment.member_id)::int AS member_count
               FROM teams team
               LEFT JOIN member_team_assignments assignment ON assignment.team_id = team.id
               WHERE EXISTS (
                   SELECT 1 FROM members actor
                   WHERE actor.account_id = ? AND actor.archived_at IS NULL AND (
                       EXISTS (SELECT 1 FROM club_capability_grants g
                               WHERE g.member_id = actor.id
                                 AND g.capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS'))
                       OR EXISTS (SELECT 1 FROM team_capability_grants g
                                  WHERE g.member_id = actor.id AND g.team_id = team.id
                                    AND g.capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS'))
                   )
               )
               GROUP BY team.id, team.name ORDER BY team.name, team.id""",
            actorAccountId,
        ).map {
            Team(
                requireNotNull(it.get("id", UUID::class.java)),
                requireNotNull(it.get("name", String::class.java)),
                requireNotNull(it.get("member_count", Int::class.java)),
            )
        }

    private fun validName(name: String): String =
        name.trim().also { require(it.length in 1..200) { "Invalid team name" } }
}
