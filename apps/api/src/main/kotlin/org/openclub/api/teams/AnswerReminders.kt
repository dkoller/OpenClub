package org.openclub.api.teams

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.notifications.Notifier
import java.util.UUID

/**
 * A coach's "remind everyone who has not answered" (OpenAPI 1.25.0, ADR-0008 addendum 29). Only holders of
 * `MANAGE_TEAMS` for the event's team, only before the event starts and while it is not cancelled, at most once every
 * [MIN_HOURS] hours per event, and only when the installation sends email. The event row is locked first, so two
 * coaches pressing at once send one reminder. Not audited (like attendance).
 */
internal class AnswerReminders(private val database: DSLContext, private val notifier: Notifier) {
    sealed interface Outcome {
        /** [reminded]: how many team members had not answered. */
        data class Done(val reminded: Int) : Outcome
        data object NotFound : Outcome
        data object Denied : Outcome
        data object Closed : Outcome
        data object Recent : Outcome
        data object EmailOff : Outcome
    }

    fun remind(actor: UUID, eventId: UUID): Outcome = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        val row = db.fetchOne(
            """SELECT ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)} AS can_manage,
                      (e.cancelled OR e.starts_at <= now()) AS closed,
                      EXISTS (SELECT 1 FROM answer_reminders r WHERE r.event_id = e.id AND NOT r.automatic
                              AND r.sent_at > now() - INTERVAL '$MIN_HOURS hours') AS recent
               FROM team_events e WHERE e.id = ? FOR NO KEY UPDATE OF e""", actor, eventId)
            ?: return@transactionResult if (holdsClubTeamCapability(db, actor)) Outcome.NotFound else Outcome.Denied
        when {
            row.get("can_manage", Boolean::class.java) != true -> Outcome.Denied
            row.get("closed", Boolean::class.java) == true -> Outcome.Closed
            !notifier.enabled -> Outcome.EmailOff
            row.get("recent", Boolean::class.java) == true -> Outcome.Recent
            else -> {
                db.execute("INSERT INTO answer_reminders (event_id, automatic) VALUES (?, FALSE)", eventId)
                Outcome.Done(notifier.answerReminders(db, eventId))
            }
        }
    }

    /** An unknown event is 404 only for a club-wide team holder; anyone else cannot tell it from a foreign one. */
    private fun holdsClubTeamCapability(db: DSLContext, actor: UUID): Boolean = db.fetchOne(
        """SELECT 1 FROM members actor JOIN club_capability_grants g ON g.member_id = actor.id
           WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")}
             AND g.capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS') LIMIT 1""", actor) != null

    companion object {
        const val MIN_HOURS = 12
    }
}
