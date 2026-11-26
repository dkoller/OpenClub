package org.openclub.api.teams

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Volunteer shifts (OpenAPI 1.16.0, ADR-0008 addendum 20). A shift belongs to one team event. Holders of
 * `MANAGE_TEAMS` for the event's team create, change and delete shifts. Who may see an event's shifts: holders of
 * `VIEW_TEAMS` or `MANAGE_TEAMS` for the team, the team's members and the guardians of its current minor members. They
 * see each shift with the public display names of its volunteers (adults' full names, minors' first name and surname
 * initial), never IDs, birth dates or contacts. A member signs up themselves, a guardian their current minor, only
 * for shifts of a team that member is assigned to, until the shift starts (not for a cancelled event) and while
 * places are free. Every check is inside the statement that changes data; signing up locks the shift so two people
 * cannot take the last place. Not audited (like attendance).
 */
internal class EventShifts(private val database: DSLContext) {
    data class Input(val title: String, val startsAt: OffsetDateTime, val endsAt: OffsetDateTime, val needed: Int)

    data class Person(val id: UUID, val firstName: String)

    data class Shift(
        val id: UUID, val title: String, val startsAt: OffsetDateTime, val endsAt: OffsetDateTime, val needed: Int,
        val volunteers: List<String>, val signedUp: List<UUID>,
    )

    sealed interface Listing {
        /** [people] are the caller and their minors who belong to the event's team, i.e. whom they may sign up. */
        data class Found(val shifts: List<Shift>, val people: List<Person>, val canManage: Boolean) : Listing
        data object NotFound : Listing
        data object Denied : Listing
    }

    sealed interface Created {
        data class Done(val id: UUID) : Created
        data object NotFound : Created
        data object Denied : Created
        data object Invalid : Created
    }

    enum class Change { DONE, NOT_FOUND, DENIED, INVALID, CONFLICT }

    enum class SignUp { DONE, CLOSED, FULL, DENIED }

    /** A shift with free places, for the managers' overview (1.23.0). No volunteer names. */
    data class Unfilled(
        val id: UUID, val title: String, val startsAt: OffsetDateTime, val endsAt: OffsetDateTime, val needed: Int,
        val filled: Int, val eventId: UUID, val eventTitle: String, val teamId: UUID, val teamName: String,
    )

    data class UnfilledList(val items: List<Unfilled>, val truncated: Boolean)

    /** The actor's own member row and their current minors. Binds the actor account. */
    private val people = """
        me AS (SELECT m.id, m.first_name FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}),
        people AS (
          SELECT me.id, me.first_name FROM me
          UNION
          SELECT minor.id, minor.first_name
          FROM me JOIN member_guardianships gs ON gs.guardian_member_id = me.id
          JOIN members guardian ON guardian.id = gs.guardian_member_id
          JOIN members minor ON minor.id = gs.minor_member_id
          JOIN clubs c ON TRUE
          WHERE ${AgeSql.adult("guardian")} AND minor.archived_at IS NULL AND ${AgeSql.minor("minor")})"""

    fun list(actor: UUID, eventId: UUID): Listing {
        val access = database.fetchOne(
            """WITH $people
               SELECT ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)} AS can_manage,
                      (${ActorSql.holdsTeamCapability("e.team_id", Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)}
                       OR EXISTS (SELECT 1 FROM member_team_assignments a JOIN people p ON p.id = a.member_id
                                  WHERE a.team_id = e.team_id)) AS can_see
               FROM team_events e WHERE e.id = ?""", actor, actor, actor, eventId)
            ?: return if (holdsClubTeamCapability(actor)) Listing.NotFound else Listing.Denied
        if (access.get("can_see", Boolean::class.java) != true) return Listing.Denied
        val mine = database.fetch(
            """WITH $people
               SELECT p.id, p.first_name FROM people p
               JOIN member_team_assignments a ON a.member_id = p.id
               JOIN team_events e ON e.team_id = a.team_id AND e.id = ?
               ORDER BY p.first_name, p.id""", actor, eventId)
            .map { Person(it.get("id", UUID::class.java), it.get("first_name", String::class.java)) }
        val ids = mine.map { it.id }.toSet()
        val shifts = database.fetch(
            """SELECT s.id, s.title, s.starts_at, s.ends_at, s.needed,
                      COALESCE(json_agg(json_build_object('id', m.id, 'name',
                                   CASE WHEN ${AgeSql.minor("m")}
                                        THEN m.first_name || ' ' || left(m.last_name, 1) || '.'
                                        ELSE m.first_name || ' ' || m.last_name END)
                               ORDER BY v.signed_up_at, m.id) FILTER (WHERE m.id IS NOT NULL), '[]'::json)::text AS volunteers
               FROM event_shifts s
               JOIN clubs c ON TRUE
               LEFT JOIN shift_volunteers v ON v.shift_id = s.id
               LEFT JOIN members m ON m.id = v.member_id AND m.archived_at IS NULL
               WHERE s.event_id = ?
               GROUP BY s.id ORDER BY s.starts_at, s.title, s.id""", eventId).map { row ->
            val volunteers = Json.parseToJsonElement(row.get("volunteers", String::class.java)).jsonArray.map { it.jsonObject }
            val names = volunteers.map { it.getValue("name").jsonPrimitive.content }
            val members = volunteers.map { UUID.fromString(it.getValue("id").jsonPrimitive.content) }
            Shift(row.get("id", UUID::class.java), row.get("title", String::class.java),
                row.get("starts_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
                row.get("ends_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
                row.get("needed", Int::class.java), names, members.filter { it in ids })
        }
        return Listing.Found(shifts, mine, access.get("can_manage", Boolean::class.java) == true)
    }

    /**
     * Shifts starting within the next [UNFILLED_DAYS] days with free places, of events that are not cancelled, for the
     * teams the actor manages (`MANAGE_TEAMS` club-wide or for the team), soonest first, at most [UNFILLED_LIMIT].
     * Null when the actor holds `MANAGE_TEAMS` nowhere. Counts only, never who signed up.
     */
    fun unfilled(actor: UUID): UnfilledList? {
        val manages = database.fetchOne(
            "SELECT ${ActorSql.holdsTeamCapability(null, Capability.MANAGE_TEAMS)} AS manages", actor)
            ?.get("manages", Boolean::class.java) == true
        if (!manages) return null
        val rows = database.fetch(
            """SELECT s.id, s.title, s.starts_at, s.ends_at, s.needed, f.filled,
                      e.id AS event_id, e.title AS event_title, t.id AS team_id, t.name AS team_name
               FROM event_shifts s
               JOIN team_events e ON e.id = s.event_id
               JOIN teams t ON t.id = e.team_id
               CROSS JOIN LATERAL (SELECT count(*)::int AS filled FROM shift_volunteers v WHERE v.shift_id = s.id) f
               WHERE NOT e.cancelled AND s.starts_at > now() AND s.starts_at <= now() + INTERVAL '$UNFILLED_DAYS days'
                 AND f.filled < s.needed
                 AND ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)}
               ORDER BY s.starts_at, t.name, s.title, s.id
               LIMIT ${UNFILLED_LIMIT + 1}""", actor).map { row ->
            Unfilled(row.get("id", UUID::class.java), row.get("title", String::class.java),
                row.get("starts_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
                row.get("ends_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
                row.get("needed", Int::class.java), row.get("filled", Int::class.java),
                row.get("event_id", UUID::class.java), row.get("event_title", String::class.java),
                row.get("team_id", UUID::class.java), row.get("team_name", String::class.java))
        }
        return UnfilledList(rows.take(UNFILLED_LIMIT), rows.size > UNFILLED_LIMIT)
    }

    fun create(actor: UUID, eventId: UUID, input: Input): Created = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        // Locks the event so the per-event limit holds under concurrent creation.
        val event = db.fetchOne(
            """SELECT ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)} AS can_manage
               FROM team_events e WHERE e.id = ? FOR NO KEY UPDATE OF e""", actor, eventId)
            ?: return@transactionResult if (holdsClubTeamCapability(db, actor)) Created.NotFound else Created.Denied
        if (event.get("can_manage", Boolean::class.java) != true) return@transactionResult Created.Denied
        val id = UUID.randomUUID()
        val inserted = db.execute(
            """INSERT INTO event_shifts (id, event_id, title, starts_at, ends_at, needed)
               SELECT ?, e.id, ?, x.s_start, x.s_end, ?
               FROM team_events e CROSS JOIN (SELECT ?::timestamptz AS s_start, ?::timestamptz AS s_end) x
               WHERE e.id = ? AND $FITS_EVENT
                 AND (SELECT count(*) FROM event_shifts s WHERE s.event_id = e.id) < $MAX_PER_EVENT
                 AND ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)}""",
            id, input.title, input.needed, input.startsAt.toString(), input.endsAt.toString(), eventId, actor)
        if (inserted == 1) Created.Done(id) else Created.Invalid
    }

    fun replace(actor: UUID, shiftId: UUID, input: Input): Change = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        // Locks the shift so a sign-up cannot slip in between the check below and the change.
        val row = db.fetchOne(
            """SELECT ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)} AS can_manage,
                      (SELECT count(*) FROM shift_volunteers v WHERE v.shift_id = s.id) AS filled
               FROM event_shifts s JOIN team_events e ON e.id = s.event_id
               WHERE s.id = ? FOR NO KEY UPDATE OF s""", actor, shiftId)
            ?: return@transactionResult missing(db, actor)
        if (row.get("can_manage", Boolean::class.java) != true) return@transactionResult Change.DENIED
        if (row.get("filled", Int::class.java) > input.needed) return@transactionResult Change.CONFLICT
        val changed = db.execute(
            """UPDATE event_shifts s SET title = ?, starts_at = x.s_start, ends_at = x.s_end, needed = ?
               FROM team_events e, (SELECT ?::timestamptz AS s_start, ?::timestamptz AS s_end) x
               WHERE s.id = ? AND e.id = s.event_id AND $FITS_EVENT
                 AND ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)}""",
            input.title, input.needed, input.startsAt.toString(), input.endsAt.toString(), shiftId, actor)
        if (changed == 1) Change.DONE else Change.INVALID
    }

    fun delete(actor: UUID, shiftId: UUID): Change {
        val deleted = database.execute(
            """DELETE FROM event_shifts s USING team_events e
               WHERE s.id = ? AND e.id = s.event_id AND ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)}""",
            shiftId, actor) == 1
        if (deleted) return Change.DONE
        return if (database.fetchOne("SELECT 1 FROM event_shifts WHERE id = ?", shiftId) != null) Change.DENIED
        else missing(database, actor)
    }

    /**
     * The shift and target member the actor may sign up (one row), with whether signing up is closed and how many
     * places are taken. Binds, in order: actor account, target member, shift.
     */
    private val allowed = """
        WITH me AS (SELECT m.id FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}),
             allowed AS (
               SELECT s.id AS shift_id, target.id AS member_id, (e.cancelled OR s.starts_at <= now()) AS closed,
                      s.needed, (SELECT count(*) FROM shift_volunteers v WHERE v.shift_id = s.id) AS filled,
                      EXISTS (SELECT 1 FROM shift_volunteers v WHERE v.shift_id = s.id AND v.member_id = target.id) AS signed_up
               FROM event_shifts s
               JOIN team_events e ON e.id = s.event_id
               JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN members target ON target.id = a.member_id AND target.archived_at IS NULL
               JOIN me ON TRUE
               JOIN clubs c ON TRUE
               WHERE a.member_id = ? AND s.id = ?
                 AND (target.id = me.id OR EXISTS (
                       SELECT 1 FROM member_guardianships gs JOIN members guardian ON guardian.id = gs.guardian_member_id
                       WHERE gs.minor_member_id = target.id AND gs.guardian_member_id = me.id
                         AND ${AgeSql.adult("guardian")} AND ${AgeSql.minor("target")}))
               -- Locks the assignment: a concurrent unassign either waits for this sign-up (and its trigger then
               -- removes it) or commits first (and this sign-up is refused).
               FOR SHARE OF a)"""

    fun signUp(actor: UUID, shiftId: UUID, memberId: UUID): SignUp = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        // First lock the shift, then count in a fresh statement: two sign-ups for the last place run one after the other.
        db.fetchOne("SELECT 1 FROM event_shifts WHERE id = ? FOR NO KEY UPDATE", shiftId) ?: return@transactionResult SignUp.DENIED
        val row = db.fetchOne("$allowed SELECT closed, needed, filled, signed_up FROM allowed", actor, memberId, shiftId)
            ?: return@transactionResult SignUp.DENIED
        when {
            row.get("signed_up", Boolean::class.java) == true -> SignUp.DONE
            row.get("closed", Boolean::class.java) == true -> SignUp.CLOSED
            row.get("filled", Int::class.java) >= row.get("needed", Int::class.java) -> SignUp.FULL
            else -> {
                val inserted = db.execute(
                    """$allowed
                       INSERT INTO shift_volunteers (shift_id, member_id)
                       SELECT shift_id, member_id FROM allowed WHERE NOT closed AND filled < needed
                       ON CONFLICT (shift_id, member_id) DO NOTHING""", actor, memberId, shiftId)
                if (inserted == 1) SignUp.DONE else SignUp.DENIED
            }
        }
    }

    fun withdraw(actor: UUID, shiftId: UUID, memberId: UUID): SignUp {
        val removed = database.fetch(
            """$allowed
               DELETE FROM shift_volunteers v USING allowed
               WHERE v.shift_id = allowed.shift_id AND v.member_id = allowed.member_id AND NOT allowed.closed
               RETURNING 1""", actor, memberId, shiftId).isNotEmpty()
        if (removed) return SignUp.DONE
        val row = database.fetchOne("$allowed SELECT closed, signed_up FROM allowed", actor, memberId, shiftId)
            ?: return SignUp.DENIED
        // Withdrawing someone who is not signed up is still a success for someone allowed to sign them up.
        return if (row.get("closed", Boolean::class.java) == true && row.get("signed_up", Boolean::class.java) == true)
            SignUp.CLOSED else SignUp.DONE
    }

    /** An unknown shift is 404 only for a club-wide team holder; anyone else cannot tell it from a foreign one. */
    private fun missing(db: DSLContext, actor: UUID): Change =
        if (holdsClubTeamCapability(db, actor)) Change.NOT_FOUND else Change.DENIED

    private fun holdsClubTeamCapability(actor: UUID) = holdsClubTeamCapability(database, actor)

    private fun holdsClubTeamCapability(db: DSLContext, actor: UUID): Boolean = ActorSql.holdsClubWide(db, actor, Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)

    companion object {
        const val MAX_PER_EVENT = 50
        const val UNFILLED_DAYS = 14
        const val UNFILLED_LIMIT = 100
        /** A shift lies between a day before the event's start and a day after its end. Uses `x.s_start`/`x.s_end`; binds nothing. */
        private const val FITS_EVENT = "x.s_start >= e.starts_at - INTERVAL '1 day' AND x.s_end <= e.ends_at + INTERVAL '1 day'"
    }
}
