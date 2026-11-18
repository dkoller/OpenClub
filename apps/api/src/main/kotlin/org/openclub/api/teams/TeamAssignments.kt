package org.openclub.api.teams

import org.jooq.DSLContext
import java.util.UUID
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess.Capability

/** Club-wide MANAGE_TEAMS; binds the actor account ID. */
private val MANAGE_TEAMS_GATE = ActorSql.holdsClubCapability(Capability.MANAGE_TEAMS)

/** MANAGE_TEAMS club-wide or on one team; binds actor account ID, then team ID. */
private val MANAGE_ONE_TEAM_GATE = ActorSql.holdsTeamCapability("?", Capability.MANAGE_TEAMS)

/** VIEW_TEAMS or MANAGE_TEAMS club-wide or on the listed `team` row; binds actor account ID. */
private val VIEW_LISTED_TEAM_GATE =
    ActorSql.holdsTeamCapability("team.id", Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)

/**
 * Internal team operations. actorAccountId must come from a verified session.
 * HTTP routes call this service with a session-derived actor; every query also
 * requires the actor's current verified account identity in the same SQL operation. Team grants allow
 * scoped assignment mutations and visibility; create/rename/delete require a club grant.
 */
internal class TeamAssignments(private val database: DSLContext) {
    data class Team(val id: UUID, val name: String, val memberCount: Int)

    fun create(actorAccountId: UUID, name: String): UUID? {
        val normalized = validName(name)
        val id = UUID.randomUUID()
        return if (database.execute(
            """INSERT INTO teams (id, name)
               SELECT ?, ? WHERE $MANAGE_TEAMS_GATE""",
            id, normalized, actorAccountId,
        ) == 1) id else null
    }

    fun rename(actorAccountId: UUID, teamId: UUID, name: String): Boolean =
        database.execute(
            """UPDATE teams SET name = ?
               WHERE id = ? AND $MANAGE_TEAMS_GATE""",
            validName(name), teamId, actorAccountId,
        ) == 1

    fun remove(actorAccountId: UUID, teamId: UUID): Boolean =
        database.execute(
            """DELETE FROM teams WHERE id = ? AND $MANAGE_TEAMS_GATE""",
            teamId, actorAccountId,
        ) == 1

    fun assign(actorAccountId: UUID, teamId: UUID, memberId: UUID): Boolean =
        database.execute(
            """INSERT INTO member_team_assignments (team_id, member_id)
               SELECT team.id, member.id FROM teams team
               JOIN members member ON member.id = ? AND member.archived_at IS NULL
               WHERE team.id = ? AND $MANAGE_ONE_TEAM_GATE FOR UPDATE OF member ON CONFLICT DO NOTHING""",
            memberId, teamId, actorAccountId, teamId,
        ) == 1

    fun unassign(actorAccountId: UUID, teamId: UUID, memberId: UUID): Boolean =
        database.execute(
            """DELETE FROM member_team_assignments
               WHERE team_id = ? AND member_id = ? AND $MANAGE_ONE_TEAM_GATE""",
            teamId, memberId, actorAccountId, teamId,
        ) == 1

    fun list(actorAccountId: UUID): List<Team> =
        database.fetch(
            """SELECT team.id, team.name, COUNT(assignment.member_id)::int AS member_count
               FROM teams team
               LEFT JOIN member_team_assignments assignment ON assignment.team_id = team.id
               WHERE $VIEW_LISTED_TEAM_GATE
               GROUP BY team.id, team.name ORDER BY team.name, team.id""",
            actorAccountId,
        ).map {
            Team(
                requireNotNull(it.get("id", UUID::class.java)),
                requireNotNull(it.get("name", String::class.java)),
                requireNotNull(it.get("member_count", Int::class.java)),
            )
        }

    companion object {
        /** Trimmed 1..200 characters; also applied at the HTTP boundary so bad names are a 400. */
        fun validName(name: String): String =
            name.trim().also { require(it.length in 1..200) { "Invalid team name" } }
    }
}
