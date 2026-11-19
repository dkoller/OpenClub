package org.openclub.api.teams

import org.jooq.DSLContext
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import java.util.UUID

/**
 * Attendance for team events (OpenAPI 1.11.0, ADR-0008 addendum 14). A member answers for themselves, a guardian for
 * their current minor, only for events of a team that member is assigned to and only until the event starts (and not
 * for a cancelled event). Who may answer is decided inside the same statement as the change. Holders of `VIEW_TEAMS`
 * or `MANAGE_TEAMS` for the team see every answer, by public display name only (adults' full names, minors' first name
 * and surname initial), never IDs, birth dates or contacts. Everyone else sees only totals.
 */
internal class EventAttendance(private val database: DSLContext) {
    enum class Response { YES, NO, MAYBE }

    enum class Change { DONE, CLOSED, DENIED }

    data class Answer(val displayName: String, val response: Response?)

    data class Counts(val yes: Int, val no: Int, val maybe: Int)

    sealed interface Listing {
        data class Found(val answers: List<Answer>, val counts: Counts) : Listing
        data object NotFound : Listing
        data object Denied : Listing
    }

    /**
     * The event and target member the actor may answer for (one row), with whether answering is closed. Binds, in order:
     * actor account, target member, event.
     */
    private val allowed = """
        WITH me AS (SELECT m.id FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}),
             allowed AS (
               SELECT e.id AS event_id, target.id AS member_id, (e.cancelled OR e.starts_at <= now()) AS closed
               FROM team_events e
               JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN members target ON target.id = a.member_id AND target.archived_at IS NULL
               JOIN me ON TRUE
               JOIN clubs c ON TRUE
               WHERE a.member_id = ? AND e.id = ?
                 AND (target.id = me.id OR EXISTS (
                       SELECT 1 FROM member_guardianships gs JOIN members guardian ON guardian.id = gs.guardian_member_id
                       WHERE gs.minor_member_id = target.id AND gs.guardian_member_id = me.id
                         AND ${AgeSql.adult("guardian")} AND ${AgeSql.minor("target")}))
               -- Locks the assignment: a concurrent unassign either waits for this answer (and its trigger then
               -- removes it) or commits first (and this answer is refused).
               FOR SHARE OF a)"""

    fun answer(actor: UUID, eventId: UUID, memberId: UUID, response: Response): Change {
        val changed = database.fetch(
            """$allowed
               INSERT INTO event_attendance (event_id, member_id, response)
               SELECT event_id, member_id, ? FROM allowed WHERE NOT closed
               ON CONFLICT (event_id, member_id) DO UPDATE SET response = EXCLUDED.response, answered_at = now()
               RETURNING 1""", actor, memberId, eventId, response.name).isNotEmpty()
        return if (changed) Change.DONE else classify(actor, eventId, memberId)
    }

    fun clear(actor: UUID, eventId: UUID, memberId: UUID): Change {
        val cleared = database.fetch(
            """$allowed
               DELETE FROM event_attendance ea USING allowed
               WHERE ea.event_id = allowed.event_id AND ea.member_id = allowed.member_id AND NOT allowed.closed
               RETURNING 1""", actor, memberId, eventId).isNotEmpty()
        // Clearing an answer that does not exist is still a success for someone allowed to answer.
        return if (cleared) Change.DONE else classify(actor, eventId, memberId)
    }

    private fun classify(actor: UUID, eventId: UUID, memberId: UUID): Change {
        val row = database.fetchOne("$allowed SELECT closed FROM allowed", actor, memberId, eventId) ?: return Change.DENIED
        return if (row.get(0, Boolean::class.java) == true) Change.CLOSED else Change.DONE
    }

    fun list(actor: UUID, eventId: UUID): Listing {
        val access = database.fetchOne(
            "SELECT ${ActorSql.holdsTeamCapability("e.team_id", Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)} FROM team_events e WHERE e.id = ?",
            actor, eventId) ?: return if (database.fetchOne(
                """SELECT 1 FROM members actor JOIN club_capability_grants g ON g.member_id = actor.id
                   WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")} AND g.capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS')""",
                actor) != null) Listing.NotFound else Listing.Denied
        if (access.get(0, Boolean::class.java) != true) return Listing.Denied
        val answers = database.fetch(
            """SELECT CASE WHEN ${AgeSql.minor("m")}
                           THEN m.first_name || ' ' || left(m.last_name, 1) || '.'
                           ELSE m.first_name || ' ' || m.last_name END AS display_name,
                      ea.response
               FROM team_events e
               JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN members m ON m.id = a.member_id AND m.archived_at IS NULL
               JOIN clubs c ON TRUE
               LEFT JOIN event_attendance ea ON ea.event_id = e.id AND ea.member_id = m.id
               WHERE e.id = ? AND ${ActorSql.holdsTeamCapability("e.team_id", Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)}
               ORDER BY display_name, m.id""", eventId, actor).map {
            Answer(it.get("display_name", String::class.java), it.get("response", String::class.java)?.let(Response::valueOf))
        }
        return Listing.Found(answers, Counts(
            answers.count { it.response == Response.YES }, answers.count { it.response == Response.NO },
            answers.count { it.response == Response.MAYBE }))
    }

    companion object {
        /** SQL columns `yes_count`, `no_count`, `maybe_count` for the event aliased `e`. Binds nothing. */
        const val COUNTS = """
            (SELECT count(*) FROM event_attendance ea JOIN member_team_assignments ta ON ta.team_id = e.team_id AND ta.member_id = ea.member_id
             WHERE ea.event_id = e.id AND ea.response = 'YES') AS yes_count,
            (SELECT count(*) FROM event_attendance ea JOIN member_team_assignments ta ON ta.team_id = e.team_id AND ta.member_id = ea.member_id
             WHERE ea.event_id = e.id AND ea.response = 'NO') AS no_count,
            (SELECT count(*) FROM event_attendance ea JOIN member_team_assignments ta ON ta.team_id = e.team_id AND ta.member_id = ea.member_id
             WHERE ea.event_id = e.id AND ea.response = 'MAYBE') AS maybe_count"""
    }
}
