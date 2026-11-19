package org.openclub.api.teams

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Team trainings and events (OpenAPI 1.10.0, ADR-0008 addendum 13). Managing needs `MANAGE_TEAMS` for the event's
 * team, club-wide or team-scoped; seeing a team's schedule needs `VIEW_TEAMS` or `MANAGE_TEAMS` for it. Every
 * member also sees the events of their own teams and of their current minors' teams ([mine]). Each write checks the
 * actor's grant inside the same SQL statement. Events hold no personal data and are not audited.
 */
internal class TeamEvents(private val database: DSLContext) {
    enum class Kind { TRAINING, MATCH, OTHER }

    data class Input(
        val kind: Kind, val title: String, val startsAt: OffsetDateTime, val endsAt: OffsetDateTime,
        val location: String?, val notes: String?,
    )

    /** Someone an event in [mine] is for: the member themselves or one of their minors. */
    data class For(val id: UUID, val firstName: String)

    data class Event(
        val id: UUID, val teamId: UUID, val teamName: String, val kind: Kind, val title: String,
        val startsAt: OffsetDateTime, val endsAt: OffsetDateTime, val location: String?, val notes: String?,
        val cancelled: Boolean, val forMembers: List<For> = emptyList(),
    )

    data class Page(val events: List<Event>, val truncated: Boolean)

    sealed interface Listing {
        data class Found(val page: Page, val canManage: Boolean) : Listing
        data object NotFound : Listing
        data object Denied : Listing
    }

    enum class Change { DONE, NOT_FOUND, DENIED }

    sealed interface Created {
        data class Done(val ids: List<UUID>) : Created
        data object NotFound : Created
        data object Denied : Created
    }

    fun holdsSomeTeamCapability(actor: UUID, vararg capabilities: Capability): Boolean = database.fetchOne(
        "SELECT ${ActorSql.holdsTeamCapability(null, *capabilities)}", actor)?.get(0, Boolean::class.java) == true

    fun isMember(actor: UUID): Boolean = database.fetchOne(
        "SELECT 1 FROM members me WHERE me.account_id = ? AND ${ActorSql.activeVerified("me")}", actor) != null

    fun listForTeam(actor: UUID, teamId: UUID, from: OffsetDateTime, to: OffsetDateTime): Listing {
        val access = database.fetchOne(
            """SELECT ${ActorSql.holdsTeamCapability("team.id", Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)} AS can_view,
                      ${ActorSql.holdsTeamCapability("team.id", Capability.MANAGE_TEAMS)} AS can_manage
               FROM teams team WHERE team.id = ?""", actor, actor, teamId)
        if (access == null) {
            // An unknown team is 404 only for a club-wide viewer; a team-scoped one cannot tell it from a foreign team.
            return if (holdsClubTeamCapability(actor)) Listing.NotFound else Listing.Denied
        }
        if (access.get("can_view", Boolean::class.java) != true) return Listing.Denied
        val rows = database.fetch(
            """SELECT e.id, e.team_id, team.name AS team_name, e.kind, e.title, e.starts_at, e.ends_at, e.location,
                      e.notes, e.cancelled
               FROM team_events e JOIN teams team ON team.id = e.team_id
               WHERE e.team_id = ? AND e.ends_at > ?::timestamptz AND e.starts_at < ?::timestamptz
                 AND ${ActorSql.holdsTeamCapability("team.id", Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)}
               ORDER BY e.starts_at, e.id LIMIT ${LIMIT + 1}""", teamId, from.toString(), to.toString(), actor)
        return Listing.Found(page(rows.map { event(it) }), access.get("can_manage", Boolean::class.java) == true)
    }

    /** The events of the actor's own teams and of their current minors' teams, each with whom it is for. */
    fun mine(actor: UUID, from: OffsetDateTime, to: OffsetDateTime): Page? {
        if (!isMember(actor)) return null
        val rows = database.fetch(
            """WITH me AS (SELECT m.id, m.first_name FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}),
                    people AS (
                      SELECT me.id, me.first_name FROM me
                      UNION
                      SELECT minor.id, minor.first_name
                      FROM me JOIN member_guardianships gs ON gs.guardian_member_id = me.id
                      JOIN members guardian ON guardian.id = gs.guardian_member_id
                      JOIN members minor ON minor.id = gs.minor_member_id
                      JOIN clubs c ON TRUE
                      WHERE ${AgeSql.adult("guardian")} AND minor.archived_at IS NULL AND ${AgeSql.minor("minor")})
               SELECT e.id, e.team_id, team.name AS team_name, e.kind, e.title, e.starts_at, e.ends_at, e.location,
                      e.notes, e.cancelled,
                      json_agg(json_build_object('id', p.id, 'firstName', p.first_name) ORDER BY p.first_name, p.id)::text AS for_members
               FROM team_events e
               JOIN teams team ON team.id = e.team_id
               JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN people p ON p.id = a.member_id
               WHERE e.ends_at > ?::timestamptz AND e.starts_at < ?::timestamptz
               GROUP BY e.id, team.name
               ORDER BY e.starts_at, e.id LIMIT ${LIMIT + 1}""", actor, from.toString(), to.toString())
        return page(rows.map { row ->
            val forMembers = Json.parseToJsonElement(row.get("for_members", String::class.java)).jsonArray.map {
                val person = it.jsonObject
                For(UUID.fromString(person.getValue("id").jsonPrimitive.content), person.getValue("firstName").jsonPrimitive.content)
            }
            event(row).copy(forMembers = forMembers)
        })
    }

    /** Creates [starts] copies of [input] (the first and its weekly repeats) in one statement, or none. */
    fun create(actor: UUID, teamId: UUID, input: Input, starts: List<OffsetDateTime>): Created {
        val duration = Duration.between(input.startsAt, input.endsAt)
        val ids = starts.map { UUID.randomUUID() }
        val inserted = database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            val allowed = db.fetchOne(
                "SELECT 1 FROM teams team WHERE team.id = ? AND ${ActorSql.holdsTeamCapability("team.id", Capability.MANAGE_TEAMS)} FOR SHARE OF team",
                teamId, actor) != null
            if (!allowed) return@transactionResult false
            starts.forEachIndexed { index, start ->
                db.execute(
                    """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, location, notes)
                       VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?)""",
                    ids[index], teamId, input.kind.name, input.title, start.toString(), start.plus(duration).toString(), input.location, input.notes)
            }
            true
        }
        if (inserted) return Created.Done(ids)
        return if (teamExists(teamId)) Created.Denied else notFoundOrDenied(actor, Created.NotFound, Created.Denied)
    }

    fun replace(actor: UUID, eventId: UUID, input: Input, cancelled: Boolean): Change {
        val changed = database.execute(
            """UPDATE team_events e SET kind = ?, title = ?, starts_at = ?::timestamptz, ends_at = ?::timestamptz, location = ?, notes = ?, cancelled = ?
               WHERE e.id = ? AND ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)}""",
            input.kind.name, input.title, input.startsAt.toString(), input.endsAt.toString(), input.location, input.notes, cancelled,
            eventId, actor) == 1
        return if (changed) Change.DONE else missing(eventId)
    }

    fun delete(actor: UUID, eventId: UUID): Change {
        val deleted = database.execute(
            "DELETE FROM team_events e WHERE e.id = ? AND ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)}",
            eventId, actor) == 1
        return if (deleted) Change.DONE else missing(eventId)
    }

    private fun missing(eventId: UUID): Change =
        if (database.fetchOne("SELECT 1 FROM team_events WHERE id = ?", eventId) != null) Change.DENIED else Change.NOT_FOUND

    private fun teamExists(teamId: UUID) = database.fetchOne("SELECT 1 FROM teams WHERE id = ?", teamId) != null

    private fun holdsClubTeamCapability(actor: UUID): Boolean = database.fetchOne(
        """SELECT 1 FROM members actor JOIN club_capability_grants g ON g.member_id = actor.id
           WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")}
             AND g.capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS')""", actor) != null

    private fun <T> notFoundOrDenied(actor: UUID, notFound: T, denied: T): T =
        if (holdsClubTeamCapability(actor)) notFound else denied

    private fun page(events: List<Event>) = Page(events.take(LIMIT), events.size > LIMIT)

    private fun event(row: Record) = Event(
        row.get("id", UUID::class.java), row.get("team_id", UUID::class.java), row.get("team_name", String::class.java),
        Kind.valueOf(row.get("kind", String::class.java)), row.get("title", String::class.java),
        row.get("starts_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
        row.get("ends_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
        row.get("location", String::class.java), row.get("notes", String::class.java),
        row.get("cancelled", Boolean::class.java),
    )

    companion object {
        /** At most this many events per response; `truncated` says that a narrower range shows the rest. */
        const val LIMIT = 500
        const val MAX_COPIES = 52

        /**
         * The start of every weekly copy from [start] up to and including the local date [until] in [zone], keeping
         * the local wall-clock time across daylight-saving changes. Null when there would be more than [MAX_COPIES].
         */
        fun weeklyStarts(start: OffsetDateTime, until: LocalDate?, zone: ZoneId?): List<OffsetDateTime>? {
            if (until == null) return listOf(start)
            requireNotNull(zone)
            val local = start.atZoneSameInstant(zone)
            if (until < local.toLocalDate()) return null
            val starts = generateSequence(local) { it.plusWeeks(1) }
                .takeWhile { !it.toLocalDate().isAfter(until) }
                .take(MAX_COPIES + 1).map { it.toOffsetDateTime() }.toList()
            return starts.takeIf { it.size <= MAX_COPIES }
        }
    }
}
