package org.openclub.api.teams

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.notifications.Notifier
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Changing several events at once (OpenAPI 1.29.0, ADR-0008 addendum 33): for one or more teams and a range of Swiss
 * dates, cancel, reinstate, give a new place, or (1.40.0) new local start and end times on its own day to every event
 * of the chosen kinds that has not started. The actor
 * needs `MANAGE_TEAMS` for every team; the update checks it again for each row in the same statement, and anything
 * short of all matching rows rolls the whole change back. A preview counts without changing anything. People get one
 * summary email per kind of change, not one per event.
 */
internal class BulkEvents(private val database: DSLContext, private val notifier: Notifier = Notifier.DISABLED) {
    enum class Action { CANCEL, REINSTATE, SET_LOCATION, SET_VENUE, SET_TIMES }

    data class Input(
        val teams: Set<UUID>, val from: LocalDate, val to: LocalDate, val kinds: Set<TeamEvents.Kind>,
        val action: Action, val location: String?, val preview: Boolean, val venueId: UUID? = null,
        /** SET_TIMES (1.40.0): local times in the club's time zone, the end after the start. */
        val startTime: LocalTime? = null, val endTime: LocalTime? = null,
    )

    sealed interface Result {
        data class Done(val matched: Int, val changed: Int) : Result
        data object NotFound : Result
        data object Denied : Result
        data object TooMany : Result
        data object UnknownVenue : Result
    }

    fun apply(actor: UUID, input: Input): Result = database.transactionResult { config ->
        val db = DSL.using(config)
        val teams = db.fetch(
            """SELECT t.id, ${ActorSql.holdsTeamCapability("t.id", Capability.MANAGE_TEAMS)} AS allowed
               FROM teams t WHERE t.id = ANY (?::uuid[]) FOR SHARE OF t""",
            actor, input.teams.map(UUID::toString).toTypedArray())
        if (teams.any { it.get("allowed", Boolean::class.java) != true }) return@transactionResult Result.Denied
        if (teams.size != input.teams.size) {
            return@transactionResult if (ActorSql.holdsClubWide(db, actor, Capability.MANAGE_TEAMS)) Result.NotFound else Result.Denied
        }
        if (input.venueId != null && db.fetchOne("SELECT 1 FROM venues WHERE id = ?", input.venueId) == null) {
            return@transactionResult Result.UnknownVenue
        }
        val selection = """e.team_id = ANY (?::uuid[]) AND e.kind = ANY (?::text[]) AND e.starts_at > now()
                 AND e.starts_at >= (?::date)::timestamp AT TIME ZONE club_time_zone()
                 AND e.starts_at < ((?::date) + 1)::timestamp AT TIME ZONE club_time_zone()"""
        val binds = arrayOf<Any>(input.teams.map(UUID::toString).toTypedArray(), input.kinds.map { it.name }.toTypedArray(),
            input.from.toString(), input.to.toString())
        val change = when (input.action) {
            Action.CANCEL -> "NOT e.cancelled"
            Action.REINSTATE -> "e.cancelled"
            Action.SET_LOCATION -> "e.location IS DISTINCT FROM ?::text"
            Action.SET_VENUE -> "e.venue_id IS DISTINCT FROM ?::uuid"
            Action.SET_TIMES -> "(e.starts_at, e.ends_at) IS DISTINCT FROM ($NEW_START, $NEW_END)"
        }
        val changeBinds = when (input.action) {
            Action.SET_LOCATION -> arrayOf<Any?>(input.location)
            Action.SET_VENUE -> arrayOf<Any?>(input.venueId?.toString())
            Action.SET_TIMES -> arrayOf<Any?>(input.startTime.toString(), input.endTime.toString())
            else -> emptyArray()
        }
        val matched = db.fetch("SELECT e.id FROM team_events e WHERE $selection AND $change FOR NO KEY UPDATE OF e",
            *binds, *changeBinds).map { it.get(0, UUID::class.java) }
        if (matched.size > MAX_EVENTS) return@transactionResult Result.TooMany
        if (input.preview || matched.isEmpty()) return@transactionResult Result.Done(matched.size, 0)
        val set = when (input.action) {
            Action.CANCEL -> "cancelled = TRUE"
            Action.REINSTATE -> "cancelled = FALSE"
            Action.SET_LOCATION -> "location = ?::text"
            Action.SET_VENUE -> "venue_id = ?::uuid"
            // The answer deadline (1.39.0) moves with the start, so it keeps its lead and stays before it.
            Action.SET_TIMES -> "starts_at = $NEW_START, ends_at = $NEW_END, answers_until = e.answers_until + ($NEW_START - e.starts_at)"
        }
        val setBinds = if (input.action == Action.SET_TIMES) arrayOf<Any?>(input.startTime.toString(), input.endTime.toString(), input.startTime.toString()) else changeBinds
        val updated = db.execute(
            """UPDATE team_events e SET $set
               WHERE e.id = ANY (?::uuid[]) AND ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)}""",
            *setBinds, matched.map(UUID::toString).toTypedArray(), actor)
        check(updated == matched.size) { "Grant changed during a bulk change" }
        notifier.eventsBulkChanged(db, matched, cancelled = input.action == Action.CANCEL)
        Result.Done(matched.size, updated)
    }

    companion object {
        const val MAX_EVENTS = 500

        /** The event's own day in the club's time zone at a new local start or end time; each binds one time. */
        private const val NEW_START = "(((e.starts_at AT TIME ZONE club_time_zone())::date + ?::time) AT TIME ZONE club_time_zone())"
        private const val NEW_END = "(((e.starts_at AT TIME ZONE club_time_zone())::date + ?::time) AT TIME ZONE club_time_zone())"
    }
}
