package org.openclub.api.announcements

import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Announcements (OpenAPI 1.14.0, ADR-0008 addendum 18). Club-wide ones need the club-wide `POST_ANNOUNCEMENTS`
 * capability, team ones `MANAGE_TEAMS` for that team (club-wide or team-scoped); the check is inside the same
 * statement as each insert, update or delete. A member sees the current club-wide announcements, those of the teams
 * they belong to, of their current minors' teams and of the teams they manage. Club content, not audited.
 */
internal class Announcements(private val database: DSLContext) {
    data class Announcement(
        val id: UUID, val teamId: UUID?, val teamName: String?, val title: String, val body: String,
        val showUntil: LocalDate, val createdAt: OffsetDateTime, val canManage: Boolean,
    )

    /** Somewhere the actor may post: the whole club (null team) or one team. */
    data class Audience(val teamId: UUID?, val teamName: String?)

    data class Page(val items: List<Announcement>, val audiences: List<Audience>)

    enum class Change { DONE, NOT_FOUND, DENIED }

    sealed interface Created {
        data class Done(val id: UUID) : Created
        data object NotFound : Created
        data object Denied : Created
    }

    /** May the actor post anywhere at all? Decides the 403 before any input is read. */
    fun mayPostSomewhere(actor: UUID): Boolean = database.fetchOne(
        """SELECT ${ActorSql.holdsClubCapability(Capability.POST_ANNOUNCEMENTS)}
                  OR ${ActorSql.holdsTeamCapability(null, Capability.MANAGE_TEAMS)}""", actor, actor)
        ?.get(0, Boolean::class.java) == true

    /** SQL: may the actor (two binds, both the actor) manage an announcement for [team] (NULL means club-wide)? */
    private fun manages(team: String) = """CASE WHEN $team IS NULL
            THEN ${ActorSql.holdsClubCapability(Capability.POST_ANNOUNCEMENTS)}
            ELSE ${ActorSql.holdsTeamCapability(team, Capability.MANAGE_TEAMS)} END"""

    fun mine(actor: UUID, today: LocalDate): Page? {
        if (database.fetchOne("SELECT 1 FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}", actor) == null) {
            return null
        }
        val items = database.fetch(
            """WITH me AS (SELECT m.id FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}),
                    people AS (
                      SELECT me.id FROM me
                      UNION
                      SELECT minor.id FROM me JOIN member_guardianships gs ON gs.guardian_member_id = me.id
                      JOIN members guardian ON guardian.id = gs.guardian_member_id
                      JOIN members minor ON minor.id = gs.minor_member_id
                      JOIN clubs c ON TRUE
                      WHERE ${AgeSql.adult("guardian")} AND minor.archived_at IS NULL AND ${AgeSql.minor("minor")})
               SELECT an.id, an.team_id, team.name AS team_name, an.title, an.body, an.show_until, an.created_at,
                      ${manages("an.team_id")} AS can_manage
               FROM announcements an LEFT JOIN teams team ON team.id = an.team_id
               WHERE an.show_until >= ?::date
                 AND (an.team_id IS NULL
                      OR EXISTS (SELECT 1 FROM member_team_assignments a JOIN people p ON p.id = a.member_id
                                 WHERE a.team_id = an.team_id)
                      OR ${ActorSql.holdsTeamCapability("an.team_id", Capability.MANAGE_TEAMS)})
               ORDER BY an.created_at DESC, an.id LIMIT 100""",
            // Binds: the member CTE, manages (2), the date, the team-manager visibility.
            actor, actor, actor, today.toString(), actor).map {
            Announcement(it.get("id", UUID::class.java), it.get("team_id", UUID::class.java), it.get("team_name", String::class.java),
                it.get("title", String::class.java), it.get("body", String::class.java), it.get("show_until", LocalDate::class.java),
                it.get("created_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
                it.get("can_manage", Boolean::class.java))
        }
        val audiences = buildList {
            if (database.fetchOne("SELECT ${ActorSql.holdsClubCapability(Capability.POST_ANNOUNCEMENTS)}", actor)
                    ?.get(0, Boolean::class.java) == true) add(Audience(null, null))
            database.fetch(
                """SELECT team.id, team.name FROM teams team
                   WHERE ${ActorSql.holdsTeamCapability("team.id", Capability.MANAGE_TEAMS)}
                   ORDER BY team.name, team.id""", actor).forEach {
                add(Audience(it.get("id", UUID::class.java), it.get("name", String::class.java)))
            }
        }
        return Page(items, audiences)
    }

    /** Club-wide holders may learn that a team or announcement does not exist; team-scoped ones get 403 instead. */
    private fun clubWide(actor: UUID): Boolean = database.fetchOne(
        """SELECT 1 FROM members actor JOIN club_capability_grants g ON g.member_id = actor.id
           WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")} AND g.capability IN ('MANAGE_TEAMS', 'POST_ANNOUNCEMENTS')""",
        actor) != null

    fun create(actor: UUID, teamId: UUID?, title: String, body: String, showUntil: LocalDate): Created {
        if (teamId != null && database.fetchOne("SELECT 1 FROM teams WHERE id = ?", teamId) == null) {
            return if (clubWide(actor)) Created.NotFound else Created.Denied
        }
        val id = UUID.randomUUID()
        val created = try { database.execute(
            """INSERT INTO announcements (id, team_id, title, body, show_until)
               SELECT ?, t.team_id, ?, ?, ?::date FROM (SELECT ?::uuid AS team_id) t
               WHERE ${manages("t.team_id")}""",
            id, title, body, showUntil.toString(), teamId, actor, actor) == 1
        } catch (failure: DataAccessException) {
            // The team was deleted after the check above.
            if (failure.sqlState() == "23503") return if (clubWide(actor)) Created.NotFound else Created.Denied
            throw failure
        }
        return if (created) Created.Done(id) else Created.Denied
    }

    fun replace(actor: UUID, id: UUID, title: String, body: String, showUntil: LocalDate): Change {
        val changed = database.execute(
            """UPDATE announcements an SET title = ?, body = ?, show_until = ?::date, updated_at = now()
               WHERE an.id = ? AND ${manages("an.team_id")}""", title, body, showUntil.toString(), id, actor, actor) == 1
        return if (changed) Change.DONE else missing(id, actor)
    }

    fun delete(actor: UUID, id: UUID): Change {
        val deleted = database.execute("DELETE FROM announcements an WHERE an.id = ? AND ${manages("an.team_id")}", id, actor, actor) == 1
        return if (deleted) Change.DONE else missing(id, actor)
    }

    private fun missing(id: UUID, actor: UUID) = when {
        database.fetchOne("SELECT 1 FROM announcements WHERE id = ?", id) != null -> Change.DENIED
        clubWide(actor) -> Change.NOT_FOUND
        else -> Change.DENIED
    }
}
