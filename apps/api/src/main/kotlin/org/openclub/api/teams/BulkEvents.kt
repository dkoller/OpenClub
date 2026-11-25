package org.openclub.api.teams

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.notifications.Notifier
import java.time.LocalDate
import java.util.UUID

/**
 * Changing several events at once (OpenAPI 1.29.0, ADR-0008 addendum 33): for one or more teams and a range of Swiss
 * dates, cancel, reinstate or give a new place to every event of the chosen kinds that has not started. The actor
 * needs `MANAGE_TEAMS` for every team; the update checks it again for each row in the same statement, and anything
 * short of all matching rows rolls the whole change back. A preview counts without changing anything. People get one
 * summary email per kind of change, not one per event.
 */
internal class BulkEvents(private val database: DSLContext, private val notifier: Notifier = Notifier.DISABLED) {
    enum class Action { CANCEL, REINSTATE, SET_LOCATION, SET_VENUE }

    data class Input(
        val teams: Set<UUID>, val from: LocalDate, val to: LocalDate, val kinds: Set<TeamEvents.Kind>,
        val action: Action, val location: String?, val preview: Boolean, val venueId: UUID? = null,
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
            val clubWide = db.fetchOne(
                """SELECT 1 FROM members actor JOIN club_capability_grants g ON g.member_id = actor.id
                   WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")} AND g.capability = 'MANAGE_TEAMS'""", actor)
            return@transactionResult if (clubWide != null) Result.NotFound else Result.Denied
        }
        if (input.venueId != null && db.fetchOne("SELECT 1 FROM venues WHERE id = ?", input.venueId) == null) {
            return@transactionResult Result.UnknownVenue
        }
        val selection = """e.team_id = ANY (?::uuid[]) AND e.kind = ANY (?::text[]) AND e.starts_at > now()
                 AND e.starts_at >= (?::date)::timestamp AT TIME ZONE 'Europe/Zurich'
                 AND e.starts_at < ((?::date) + 1)::timestamp AT TIME ZONE 'Europe/Zurich'"""
        val binds = arrayOf<Any>(input.teams.map(UUID::toString).toTypedArray(), input.kinds.map { it.name }.toTypedArray(),
            input.from.toString(), input.to.toString())
        val change = when (input.action) {
            Action.CANCEL -> "NOT e.cancelled"
            Action.REINSTATE -> "e.cancelled"
            Action.SET_LOCATION -> "e.location IS DISTINCT FROM ?::text"
            Action.SET_VENUE -> "e.venue_id IS DISTINCT FROM ?::uuid"
        }
        val changeBinds = when (input.action) {
            Action.SET_LOCATION -> arrayOf<Any?>(input.location)
            Action.SET_VENUE -> arrayOf<Any?>(input.venueId?.toString())
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
        }
        val updated = db.execute(
            """UPDATE team_events e SET $set
               WHERE e.id = ANY (?::uuid[]) AND ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)}""",
            *changeBinds, matched.map(UUID::toString).toTypedArray(), actor)
        check(updated == matched.size) { "Grant changed during a bulk change" }
        notifier.eventsBulkChanged(db, matched, cancelled = input.action == Action.CANCEL)
        Result.Done(matched.size, updated)
    }

    companion object {
        const val MAX_EVENTS = 500
    }
}
