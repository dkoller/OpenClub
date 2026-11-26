package org.openclub.api.teams

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.notifications.Notifier
import java.util.UUID

/**
 * Coach messages (OpenAPI 1.31.0, ADR-0008 addendum 35). A holder of `MANAGE_TEAMS` for the event's team emails a part
 * of the event's people through the server: everyone in the team, those who said yes, those without an answer, or
 * the squad of a match. The team row is locked while counting the day's messages, so the limit of [DAILY_LIMIT] per
 * team holds under concurrent sends. Only the time, audience and number of members are kept, never the text.
 */
internal class CoachMessages(private val database: DSLContext, private val notifier: Notifier) {
    enum class Audience { ALL, YES, NO_ANSWER, SQUAD }

    sealed interface Outcome {
        data class Sent(val members: Int) : Outcome
        data object NotFound : Outcome
        data object Denied : Outcome
        data object EmailOff : Outcome
        data object Limit : Outcome
        data object NotMatch : Outcome
        data object Closed : Outcome
    }

    fun send(actor: UUID, eventId: UUID, audience: Audience, subject: String, body: String): Outcome = database.transactionResult { config ->
        val db = DSL.using(config)
        val event = db.fetchOne(
            """SELECT e.team_id, e.kind, e.ends_at > now() - INTERVAL '1 day' AS open,
                      ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)} AS allowed
               FROM team_events e WHERE e.id = ?""", actor, eventId)
            // An unknown event is 404 only for a club-wide manager; a team-scoped one cannot tell it from a foreign one.
            ?: return@transactionResult if (ActorSql.holdsClubWide(db, actor, Capability.MANAGE_TEAMS)) Outcome.NotFound else Outcome.Denied
        if (event.get("allowed", Boolean::class.java) != true) return@transactionResult Outcome.Denied
        if (!notifier.enabled) return@transactionResult Outcome.EmailOff
        if (event.get("open", Boolean::class.java) != true) return@transactionResult Outcome.Closed
        if (audience == Audience.SQUAD && event.get("kind", String::class.java) != "MATCH") return@transactionResult Outcome.NotMatch
        val team = event.get("team_id", UUID::class.java)
        db.execute("SELECT 1 FROM teams WHERE id = ? FOR UPDATE", team)
        val today = (db.fetchValue(
            "SELECT count(*) FROM coach_messages WHERE team_id = ? AND sent_at > now() - INTERVAL '1 day'", team) as Number).toInt()
        if (today >= DAILY_LIMIT) return@transactionResult Outcome.Limit
        val filter = when (audience) {
            Audience.ALL -> "TRUE"
            Audience.YES -> "EXISTS (SELECT 1 FROM effective_attendance ea WHERE ea.event_id = e.id AND ea.member_id = m.id AND ea.response = 'YES')"
            Audience.NO_ANSWER -> "NOT EXISTS (SELECT 1 FROM effective_attendance ea WHERE ea.event_id = e.id AND ea.member_id = m.id)"
            Audience.SQUAD -> "EXISTS (SELECT 1 FROM squad_members sm WHERE sm.event_id = e.id AND sm.member_id = m.id)"
        }
        val members = db.fetch(
            """SELECT m.id FROM team_events e
               JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN members m ON m.id = a.member_id AND m.archived_at IS NULL
               WHERE e.id = ? AND $filter ORDER BY m.id""", eventId).map { it.get(0, UUID::class.java) }
        val sender = db.fetchOne(
            "SELECT m.first_name || ' ' || m.last_name FROM members m WHERE m.account_id = ? AND m.archived_at IS NULL", actor)
            ?.get(0, String::class.java).orEmpty()
        notifier.coachMessage(db, eventId, members, sender, subject, body)
        db.execute("INSERT INTO coach_messages (id, event_id, team_id, audience, members) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), eventId, team, audience.name, members.size)
        Outcome.Sent(members.size)
    }

    companion object {
        const val DAILY_LIMIT = 10
    }
}
