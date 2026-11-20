package org.openclub.api.teams

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
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
 * Carpooling (OpenAPI 1.21.0, ADR-0008 addendum 25). A signed-in adult who belongs to the event's team, or who is the
 * guardian of a current minor in it, offers one ride per event. Team members book a seat for themselves, guardians
 * for their current minors, until departure (not for a cancelled event) and while seats are free; the ride is locked,
 * so the last seat goes to one person. The team (members, guardians, `VIEW_TEAMS`/`MANAGE_TEAMS` holders) sees each
 * ride with display names. Phone numbers (owner decision) go only between a driver and the people they carry: the
 * driver sees each passenger's phone (for a minor, the phone of the guardian who booked), and whoever booked sees
 * the driver's. Not audited. Rides go with their event and are deleted 12 months after it.
 */
internal class Rides(private val database: DSLContext) {
    enum class Direction { THERE, BACK, BOTH }

    data class Input(val direction: Direction, val seats: Int, val meetingPoint: String, val departsAt: OffsetDateTime)
    data class Person(val id: UUID, val firstName: String)
    data class Passenger(val displayName: String, val phone: String?)
    data class Ride(
        val id: UUID, val driver: String, val mine: Boolean, val direction: Direction, val seats: Int, val meetingPoint: String,
        val departsAt: OffsetDateTime, val passengers: List<Passenger>, val booked: List<UUID>, val driverPhone: String?,
    )

    sealed interface Listing {
        data class Found(val rides: List<Ride>, val people: List<Person>, val canOffer: Boolean) : Listing
        data object NotFound : Listing
        data object Denied : Listing
    }

    sealed interface Created {
        data class Done(val id: UUID) : Created
        data object Invalid : Created
        data object Exists : Created
        data object NotFound : Created
        data object Denied : Created
    }

    enum class Change { DONE, INVALID, CONFLICT, NOT_FOUND, DENIED }
    enum class Booking { DONE, CLOSED, FULL, DENIED }

    /** The actor (`me`) and their current minors (`people`). Binds the actor account. */
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

    /** True when the adult `me` may drive for the team `e.team_id`: in the team, or guardian of a minor in it. */
    private val mayDrive = """EXISTS (
        SELECT 1 FROM me JOIN members driver ON driver.id = me.id JOIN clubs c ON TRUE
        WHERE ${AgeSql.adult("driver")}
          AND EXISTS (SELECT 1 FROM people p JOIN member_team_assignments a ON a.member_id = p.id WHERE a.team_id = e.team_id))"""

    fun list(actor: UUID, eventId: UUID): Listing {
        val access = database.fetchOne(
            """WITH $people
               SELECT (${ActorSql.holdsTeamCapability("e.team_id", Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)}
                       OR EXISTS (SELECT 1 FROM member_team_assignments a JOIN people p ON p.id = a.member_id
                                  WHERE a.team_id = e.team_id)) AS can_see,
                      $mayDrive AS can_offer,
                      (SELECT id FROM me) AS me_id
               FROM team_events e WHERE e.id = ?""", actor, actor, eventId)
            ?: return if (clubWideTeamHolder(actor)) Listing.NotFound else Listing.Denied
        if (access.get("can_see", Boolean::class.java) != true) return Listing.Denied
        val me = access.get("me_id", UUID::class.java)
        val mine = database.fetch(
            """WITH $people
               SELECT p.id, p.first_name FROM people p
               JOIN member_team_assignments a ON a.member_id = p.id
               JOIN team_events e ON e.team_id = a.team_id AND e.id = ?
               ORDER BY p.first_name, p.id""", actor, eventId)
            .map { Person(it.get(0, UUID::class.java), it.get(1, String::class.java)) }
        val own = database.fetch("WITH $people SELECT id FROM people", actor).map { it.get(0, UUID::class.java) }.toSet()
        val rides = database.fetch(
            """SELECT r.id, r.driver_member_id, d.first_name || ' ' || d.last_name AS driver, d.phone AS driver_phone,
                      r.direction, r.seats, r.meeting_point, r.departs_at,
                      COALESCE(json_agg(json_build_object(
                          'member', m.id, 'bookedBy', p.booked_by,
                          'name', CASE WHEN ${AgeSql.minor("m")} THEN m.first_name || ' ' || left(m.last_name, 1) || '.'
                                       ELSE m.first_name || ' ' || m.last_name END,
                          'phone', CASE WHEN ${AgeSql.minor("m")} THEN booker.phone ELSE m.phone END)
                        ORDER BY p.booked_at, m.id) FILTER (WHERE m.id IS NOT NULL), '[]'::json)::text AS passengers
               FROM event_rides r
               JOIN members d ON d.id = r.driver_member_id
               JOIN clubs c ON TRUE
               LEFT JOIN ride_passengers p ON p.ride_id = r.id
               LEFT JOIN members m ON m.id = p.member_id AND m.archived_at IS NULL
               LEFT JOIN members booker ON booker.id = p.booked_by
               WHERE r.event_id = ?
               GROUP BY r.id, d.id
               ORDER BY r.departs_at, r.id""", eventId).map { row ->
            val passengers = Json.parseToJsonElement(row.get("passengers", String::class.java)).jsonArray.map { it.jsonObject }
            val isDriver = me != null && row.get("driver_member_id", UUID::class.java) == me
            val bookedHere = passengers.filter {
                UUID.fromString(it.getValue("member").jsonPrimitive.content) in own ||
                    it.getValue("bookedBy").jsonPrimitive.contentOrNull == me?.toString()
            }
            Ride(
                row.get("id", UUID::class.java), row.get("driver", String::class.java), isDriver,
                Direction.valueOf(row.get("direction", String::class.java)), row.get("seats", Int::class.java),
                row.get("meeting_point", String::class.java),
                row.get("departs_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
                passengers.map {
                    Passenger(it.getValue("name").jsonPrimitive.content,
                        if (isDriver) it.getValue("phone").jsonPrimitive.contentOrNull else null)
                },
                bookedHere.map { UUID.fromString(it.getValue("member").jsonPrimitive.content) }.filter { it in own },
                if (!isDriver && bookedHere.isNotEmpty()) row.get("driver_phone", String::class.java) else null,
            )
        }
        return Listing.Found(rides, mine, access.get("can_offer", Boolean::class.java) == true)
    }

    fun create(actor: UUID, eventId: UUID, input: Input): Created {
        val id = UUID.randomUUID()
        val inserted = try {
            database.execute(
                """WITH $people
                   INSERT INTO event_rides (id, event_id, driver_member_id, direction, seats, meeting_point, departs_at)
                   SELECT ?, e.id, me.id, ?, ?, ?, x.departs FROM team_events e
                   CROSS JOIN me CROSS JOIN (SELECT ?::timestamptz AS departs) x
                   WHERE e.id = ? AND $mayDrive AND $FITS_EVENT""",
                actor, id, input.direction.name, input.seats, input.meetingPoint, input.departsAt.toString(), eventId)
        } catch (failure: org.jooq.exception.DataAccessException) {
            if (failure.sqlState() == "23505") return Created.Exists else throw failure
        }
        if (inserted == 1) return Created.Done(id)
        val event = database.fetchOne(
            "WITH $people SELECT $mayDrive AS may FROM team_events e WHERE e.id = ?", actor, eventId)
            ?: return if (clubWideTeamHolder(actor)) Created.NotFound else Created.Denied
        return if (event.get(0, Boolean::class.java) == true) Created.Invalid else Created.Denied
    }

    /** The driver changes their own ride; fewer seats than bookings is [Change.CONFLICT]. */
    fun replace(actor: UUID, rideId: UUID, input: Input): Change = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        val row = db.fetchOne(
            """WITH $people
               SELECT (r.driver_member_id = (SELECT id FROM me)) AS is_driver,
                      (SELECT count(*) FROM ride_passengers p WHERE p.ride_id = r.id) AS booked
               FROM event_rides r WHERE r.id = ? FOR NO KEY UPDATE OF r""", actor, rideId)
            ?: return@transactionResult missing(db, actor)
        if (row.get("is_driver", Boolean::class.java) != true) return@transactionResult Change.DENIED
        if (row.get("booked", Int::class.java) > input.seats) return@transactionResult Change.CONFLICT
        val changed = db.execute(
            """UPDATE event_rides r SET direction = ?, seats = ?, meeting_point = ?, departs_at = x.departs
               FROM team_events e, (SELECT ?::timestamptz AS departs) x
               WHERE r.id = ? AND e.id = r.event_id AND $FITS_EVENT""",
            input.direction.name, input.seats, input.meetingPoint, input.departsAt.toString(), rideId)
        if (changed == 1) Change.DONE else Change.INVALID
    }

    /** The driver withdraws their ride, or a team manager removes it; its bookings go with it. */
    fun delete(actor: UUID, rideId: UUID): Change {
        val deleted = database.execute(
            """WITH $people
               DELETE FROM event_rides r USING team_events e
               WHERE r.id = ? AND e.id = r.event_id
                 AND (r.driver_member_id = (SELECT id FROM me)
                      OR ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)})""",
            actor, rideId, actor) == 1
        if (deleted) return Change.DONE
        return if (database.fetchOne("SELECT 1 FROM event_rides WHERE id = ?", rideId) != null) Change.DENIED else missing(database, actor)
    }

    /**
     * The ride and passenger the actor may book (one row), with whether booking is closed and how many seats are taken.
     * Binds, in order: actor account, passenger, ride.
     */
    private val allowed = """
        WITH $people,
             allowed AS (
               SELECT r.id AS ride_id, p.id AS member_id, (SELECT id FROM me) AS booked_by,
                      (e.cancelled OR r.departs_at <= now()) AS closed, r.seats,
                      (SELECT count(*) FROM ride_passengers rp WHERE rp.ride_id = r.id) AS taken,
                      EXISTS (SELECT 1 FROM ride_passengers rp WHERE rp.ride_id = r.id AND rp.member_id = p.id) AS booked
               FROM event_rides r
               JOIN team_events e ON e.id = r.event_id
               JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN people p ON p.id = a.member_id
               WHERE p.id = ? AND r.id = ?
               -- A concurrent unassign waits for this booking (and its trigger then removes it) or wins first.
               FOR SHARE OF a)"""

    fun book(actor: UUID, rideId: UUID, memberId: UUID): Booking = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        // Lock the ride first, then count in a fresh statement: two bookings for the last seat run one after the other.
        db.fetchOne("SELECT 1 FROM event_rides WHERE id = ? FOR NO KEY UPDATE", rideId) ?: return@transactionResult Booking.DENIED
        val row = db.fetchOne("$allowed SELECT closed, seats, taken, booked FROM allowed", actor, memberId, rideId)
            ?: return@transactionResult Booking.DENIED
        when {
            row.get("booked", Boolean::class.java) == true -> Booking.DONE
            row.get("closed", Boolean::class.java) == true -> Booking.CLOSED
            row.get("taken", Int::class.java) >= row.get("seats", Int::class.java) -> Booking.FULL
            else -> {
                val inserted = db.execute(
                    """$allowed
                       INSERT INTO ride_passengers (ride_id, member_id, booked_by)
                       SELECT ride_id, member_id, booked_by FROM allowed WHERE NOT closed AND taken < seats
                       ON CONFLICT (ride_id, member_id) DO NOTHING""", actor, memberId, rideId)
                if (inserted == 1) Booking.DONE else Booking.DENIED
            }
        }
    }

    fun cancelBooking(actor: UUID, rideId: UUID, memberId: UUID): Booking {
        val removed = database.fetch(
            """$allowed
               DELETE FROM ride_passengers rp USING allowed
               WHERE rp.ride_id = allowed.ride_id AND rp.member_id = allowed.member_id AND NOT allowed.closed
               RETURNING 1""", actor, memberId, rideId).isNotEmpty()
        if (removed) return Booking.DONE
        val row = database.fetchOne("$allowed SELECT closed, booked FROM allowed", actor, memberId, rideId) ?: return Booking.DENIED
        return if (row.get("closed", Boolean::class.java) == true && row.get("booked", Boolean::class.java) == true)
            Booking.CLOSED else Booking.DONE
    }

    private fun missing(db: DSLContext, actor: UUID): Change = if (clubWideTeamHolder(db, actor)) Change.NOT_FOUND else Change.DENIED

    private fun clubWideTeamHolder(actor: UUID) = clubWideTeamHolder(database, actor)

    private fun clubWideTeamHolder(db: DSLContext, actor: UUID): Boolean = db.fetchOne(
        """SELECT 1 FROM members actor JOIN club_capability_grants g ON g.member_id = actor.id
           WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")}
             AND g.capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS') LIMIT 1""", actor) != null

    companion object {
        /** Departure between a day before the event's start and its end. Uses `x.departs`; binds nothing. */
        private const val FITS_EVENT = "x.departs >= e.starts_at - INTERVAL '1 day' AND x.departs <= e.ends_at"
    }
}
