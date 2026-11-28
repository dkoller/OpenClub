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
 *
 * Ride requests (OpenAPI 1.41.0, ADR-0008 addendum 44): a member, or a guardian for their current minor, says the
 * person needs a ride (there, back or both) until the event starts; one per person and event. They are seen like the
 * rides, by display name and never with a phone, and disappear when the person books a seat on a ride covering the
 * direction. The first request of an event tells its drivers, without a name.
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

    /** A ride request (1.41.0); [memberId] only for the caller and their minors, so they can withdraw it. */
    data class Request(val displayName: String, val direction: Direction, val memberId: UUID?)

    sealed interface Listing {
        data class Found(val rides: List<Ride>, val people: List<Person>, val canOffer: Boolean, val requests: List<Request> = emptyList()) : Listing
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

    /** [first]: this was the event's only request, so its drivers are told (1.41.0). */
    data class Requested(val change: Booking, val first: Boolean = false)

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

    /** True when the adult `me` may drive for the team `e.team_id`: in the team, or guardian of a minor in it. Binds nothing. */
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
                          'phone', CASE WHEN NOT ${AgeSql.minor("m")} THEN m.phone
                                        WHEN booker.archived_at IS NULL AND EXISTS (
                                          SELECT 1 FROM member_guardianships bg
                                          WHERE bg.guardian_member_id = booker.id AND bg.minor_member_id = m.id)
                                        THEN booker.phone END)
                        ORDER BY p.booked_at, m.id) FILTER (WHERE m.id IS NOT NULL), '[]'::json)::text AS passengers
               FROM event_rides r
               JOIN team_events e ON e.id = r.event_id
               JOIN members d ON d.id = r.driver_member_id
               JOIN clubs c ON TRUE
               LEFT JOIN ride_passengers p ON p.ride_id = r.id
               LEFT JOIN members m ON m.id = p.member_id AND m.archived_at IS NULL
               LEFT JOIN members booker ON booker.id = p.booked_by
               WHERE r.event_id = ? AND $ELIGIBLE_DRIVER
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
        val requests = database.fetch(
            """WITH $people
               SELECT q.member_id, q.direction, q.member_id IN (SELECT id FROM people) AS own,
                      CASE WHEN ${AgeSql.minor("m")} THEN m.first_name || ' ' || left(m.last_name, 1) || '.'
                           ELSE m.first_name || ' ' || m.last_name END AS name
               FROM ride_requests q
               JOIN team_events e ON e.id = q.event_id
               JOIN member_team_assignments a ON a.team_id = e.team_id AND a.member_id = q.member_id
               JOIN members m ON m.id = q.member_id AND m.archived_at IS NULL
               JOIN clubs c ON TRUE
               WHERE q.event_id = ?
               ORDER BY q.requested_at, q.member_id""", actor, eventId).map {
            Request(it.get("name", String::class.java), Direction.valueOf(it.get("direction", String::class.java)),
                if (it.get("own", Boolean::class.java) == true) it.get("member_id", UUID::class.java) else null)
        }
        return Listing.Found(rides, mine, access.get("can_offer", Boolean::class.java) == true, requests)
    }

    /**
     * The event and the person (the actor or their current minor, assigned to the event's team) a request is for, with
     * whether it is closed (started or cancelled). Binds, in order: actor account, event, member.
     */
    private val requestable = """
        WITH $people,
             target AS (
               SELECT e.id AS event_id, p.id AS member_id, (e.cancelled OR e.starts_at <= now()) AS closed
               FROM team_events e
               JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN people p ON p.id = a.member_id
               WHERE e.id = ? AND p.id = ?
               -- A concurrent unassign waits for this request (and its trigger then removes it) or wins first.
               FOR SHARE OF a)"""

    /** Asks for a ride for [memberId], or changes the direction of their request (1.41.0). */
    fun request(actor: UUID, eventId: UUID, memberId: UUID, direction: Direction): Requested = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        // Requests for one event take turns, so exactly one of two simultaneous first requests tells the drivers.
        db.fetch("SELECT pg_advisory_xact_lock(hashtextextended('ride_requests:' || ?::text, 0))", eventId.toString())
        val inserted = db.fetchOne(
            """$requestable
               INSERT INTO ride_requests (event_id, member_id, direction, requested_by)
               SELECT event_id, member_id, ?, (SELECT id FROM me) FROM target WHERE NOT closed
               ON CONFLICT (event_id, member_id) DO UPDATE SET direction = EXCLUDED.direction
               RETURNING (xmax = 0) AS inserted""", actor, eventId, memberId, direction.name)
            ?: return@transactionResult Requested(classify(db, actor, eventId, memberId))
        val first = inserted.get("inserted", Boolean::class.java) == true &&
            (db.fetchValue("SELECT count(*) FROM ride_requests WHERE event_id = ?", eventId) as Number).toInt() == 1
        Requested(Booking.DONE, first)
    }

    /** Withdraws the request of [memberId]; withdrawing a missing one is also done. */
    fun withdrawRequest(actor: UUID, eventId: UUID, memberId: UUID): Booking {
        val removed = database.fetch(
            """$requestable
               DELETE FROM ride_requests q USING target
               WHERE q.event_id = target.event_id AND q.member_id = target.member_id AND NOT target.closed
               RETURNING 1""", actor, eventId, memberId).isNotEmpty()
        return if (removed) Booking.DONE else classify(database, actor, eventId, memberId)
    }

    private fun classify(db: DSLContext, actor: UUID, eventId: UUID, memberId: UUID): Booking {
        val row = db.fetchOne("$requestable SELECT closed FROM target", actor, eventId, memberId) ?: return Booking.DENIED
        return if (row.get(0, Boolean::class.java) == true) Booking.CLOSED else Booking.DONE
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
        // A driver who left the team or is no longer a guardian there may not change the offer any more.
        val eligible = db.fetchOne(
            """SELECT 1 FROM event_rides r JOIN team_events e ON e.id = r.event_id
               JOIN members d ON d.id = r.driver_member_id JOIN clubs c ON TRUE
               WHERE r.id = ? AND $ELIGIBLE_DRIVER""", rideId) != null
        if (!eligible) return@transactionResult Change.DENIED
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
               JOIN members d ON d.id = r.driver_member_id
               JOIN clubs c ON TRUE
               JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN people p ON p.id = a.member_id
               WHERE p.id = ? AND r.id = ? AND $ELIGIBLE_DRIVER
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
                if (inserted != 1) return@transactionResult Booking.DENIED
                // A booked seat answers the person's ride request when the ride covers its direction (1.41.0).
                db.execute(
                    """DELETE FROM ride_requests q USING event_rides r
                       WHERE r.id = ? AND q.event_id = r.event_id AND q.member_id = ?
                         AND (r.direction = 'BOTH' OR r.direction = q.direction)""", rideId, memberId)
                Booking.DONE
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

    private fun clubWideTeamHolder(db: DSLContext, actor: UUID): Boolean = ActorSql.holdsClubWide(db, actor, Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)

    companion object {
        /** Departure between a day before the event's start and its end. Uses `x.departs`; binds nothing. */
        private const val FITS_EVENT = "x.departs >= e.starts_at - INTERVAL '1 day' AND x.departs <= e.ends_at"

        /**
         * The driver `d` may still drive for `e.team_id` (security review 2026-11-26): an active adult with a login in
         * the team, or the current guardian of an active minor in it. An offer whose driver left, was archived or is no
         * longer a guardian there is hidden and cannot be booked or changed, so nobody keeps seeing their phone.
         * Needs `clubs c`; binds nothing.
         */
        private val ELIGIBLE_DRIVER = """(${ActorSql.activeVerified("d")} AND ${AgeSql.adult("d")} AND (
            EXISTS (SELECT 1 FROM member_team_assignments da WHERE da.member_id = d.id AND da.team_id = e.team_id)
            OR EXISTS (SELECT 1 FROM member_guardianships dg JOIN members dm ON dm.id = dg.minor_member_id
                       JOIN member_team_assignments dma ON dma.member_id = dm.id AND dma.team_id = e.team_id
                       WHERE dg.guardian_member_id = d.id AND dm.archived_at IS NULL AND ${AgeSql.minor("dm")})))"""
    }
}
