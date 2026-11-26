package org.openclub.api.teams

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.notifications.Notifier
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Match squads (OpenAPI 1.28.0, ADR-0008 addendum 32). Only holders of `MANAGE_TEAMS` for the match's team read or
 * save the squad, checked in the same statement as the read and under a lock on the event row for the save. Only
 * active team members can be called; only matches have a squad, and it can be changed until the match starts.
 * Members called for the first time are emailed (guardians for a minor) in the same transaction.
 */
internal class EventSquads(private val database: DSLContext, private val notifier: Notifier = Notifier.DISABLED) {
    data class Person(val memberId: UUID, val displayName: String, val response: String?, val called: Boolean)

    sealed interface Listing {
        data class Found(val open: Boolean, val meetAt: OffsetDateTime?, val meetingPoint: String?, val people: List<Person>) : Listing
        data object NotFound : Listing
        data object Denied : Listing
        data object NotMatch : Listing
    }

    enum class Change { DONE, NOT_FOUND, DENIED, NOT_MATCH, CLOSED, INVALID }

    data class Input(val members: Set<UUID>, val meetAt: OffsetDateTime?, val meetingPoint: String?)

    // An unknown ID is 404 only for a club-wide MANAGE_TEAMS holder; a team-scoped coach cannot tell it from another
    // team's (security review 2026-11-26).
    private fun managesSomeTeam(actor: UUID): Boolean = ActorSql.holdsClubWide(database, actor, Capability.MANAGE_TEAMS)

    private val access = """SELECT ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)} AS allowed,
               e.kind = 'MATCH' AS is_match, (NOT e.cancelled AND e.starts_at > now()) AS open
        FROM team_events e WHERE e.id = ?"""

    fun read(actor: UUID, eventId: UUID): Listing {
        val event = database.fetchOne(access, actor, eventId)
            ?: return if (managesSomeTeam(actor)) Listing.NotFound else Listing.Denied
        if (event.get("allowed", Boolean::class.java) != true) return Listing.Denied
        if (event.get("is_match", Boolean::class.java) != true) return Listing.NotMatch
        val squad = database.fetchOne("SELECT meet_at, meeting_point FROM event_squads WHERE event_id = ?", eventId)
        val people = database.fetch(
            """SELECT m.id,
                      CASE WHEN ${AgeSql.minor("m")} THEN m.first_name || ' ' || left(m.last_name, 1) || '.'
                           ELSE m.first_name || ' ' || m.last_name END AS display_name,
                      ea.response, sm.member_id IS NOT NULL AS called
               FROM team_events e
               JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN members m ON m.id = a.member_id AND m.archived_at IS NULL
               JOIN clubs c ON TRUE
               LEFT JOIN effective_attendance ea ON ea.event_id = e.id AND ea.member_id = m.id
               LEFT JOIN squad_members sm ON sm.event_id = e.id AND sm.member_id = m.id
               WHERE e.id = ?
               ORDER BY display_name, m.id""", eventId).map {
            Person(it.get("id", UUID::class.java), it.get("display_name", String::class.java),
                it.get("response", String::class.java), it.get("called", Boolean::class.java))
        }
        return Listing.Found(event.get("open", Boolean::class.java) == true, squad?.get("meet_at", OffsetDateTime::class.java),
            squad?.get("meeting_point", String::class.java), people)
    }

    /** Replaces the squad and emails the members who were not in it before. */
    fun replace(actor: UUID, eventId: UUID, input: Input): Change = database.transactionResult { config ->
        val db = DSL.using(config)
        val event = db.fetchOne("$access FOR UPDATE OF e", actor, eventId)
            ?: return@transactionResult if (EventSquads(db).managesSomeTeam(actor)) Change.NOT_FOUND else Change.DENIED
        if (event.get("allowed", Boolean::class.java) != true) return@transactionResult Change.DENIED
        if (event.get("is_match", Boolean::class.java) != true) return@transactionResult Change.NOT_MATCH
        if (event.get("open", Boolean::class.java) != true) return@transactionResult Change.CLOSED
        // Lock the assignments (as attendance and shifts do): a concurrent unassign waits, and its trigger then
        // removes the member from the squad, or wins first and the member is refused here.
        val team = db.fetch(
            """SELECT a.member_id FROM team_events e JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN members m ON m.id = a.member_id AND m.archived_at IS NULL WHERE e.id = ? FOR SHARE OF a""", eventId)
            .map { it.get(0, UUID::class.java) }.toSet()
        if (!team.containsAll(input.members)) return@transactionResult Change.INVALID
        db.execute(
            """INSERT INTO event_squads (event_id, meet_at, meeting_point) VALUES (?, ?::timestamptz, ?)
               ON CONFLICT (event_id) DO UPDATE SET meet_at = EXCLUDED.meet_at, meeting_point = EXCLUDED.meeting_point,
                   updated_at = now()""", eventId, input.meetAt?.toString(), input.meetingPoint)
        val before = db.fetch("SELECT member_id FROM squad_members WHERE event_id = ?", eventId)
            .map { it.get(0, UUID::class.java) }.toSet()
        val ids = input.members.map(UUID::toString).toTypedArray()
        db.execute("DELETE FROM squad_members WHERE event_id = ? AND NOT (member_id = ANY (?::uuid[]))", eventId, ids)
        db.execute(
            """INSERT INTO squad_members (event_id, member_id) SELECT ?, u::uuid FROM unnest(?::text[]) AS u
               ON CONFLICT DO NOTHING""", eventId, ids)
        notifier.squadCalled(db, eventId, input.members - before)
        Change.DONE
    }
}
