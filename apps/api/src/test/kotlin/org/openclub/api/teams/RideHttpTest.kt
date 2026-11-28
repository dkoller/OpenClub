package org.openclub.api.teams

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.database.RetentionCleanup
import org.openclub.api.members.MemberExport
import org.openclub.api.module
import org.openclub.api.notifications.MailConfig
import org.openclub.api.notifications.Notifier
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.openclub.api.checkContract

/**
 * Carpooling (OpenAPI 1.21.0): who offers and books, seat limits and closing, and phones only between a driver and
 * the people they carry. Each test makes its own event, so the order of tests does not matter.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RideHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val team = UUID.randomUUID()
    private val otherTeam = UUID.randomUUID()
    private val kid = UUID.randomUUID()
    private val notifier = Notifier(requireNotNull(MailConfig.fromEnvironment(mapOf(
        "OPENCLUB_SMTP_HOST" to "mail.example.invalid", "OPENCLUB_SMTP_USERNAME" to "u", "OPENCLUB_SMTP_PASSWORD" to "p",
        "OPENCLUB_SMTP_FROM" to "club@example.invalid"))), origin)
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { db ->
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Ride Club', ?)", club, "ride-$club")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)",
                team, "Ride A ${team.toString().take(8)}", otherTeam, "Ride B ${otherTeam.toString().take(8)}")
            // driver and rider play in A; guardian has the kid in A; coach manages A; viewer views A; outsider plays in B.
            for ((index, handle) in listOf("driver", "rider", "guardian", "coach", "viewer", "outsider", "stranger").withIndex()) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "ride-$account")
                if (handle == "stranger") continue
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Ride', DATE '1980-01-01', 'r@example.invalid', ?)""", it, account, handle, "+4100000007$index")
                }
            }
            for (handle in listOf("driver", "rider")) db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, memberOf.getValue(handle))
            db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", otherTeam, memberOf.getValue("outsider"))
            db.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Ride', CURRENT_DATE - 3650)", kid)
            db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, kid)
            db.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
            db.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')", team, memberOf.getValue("coach"))
            db.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'VIEW_TEAMS')", team, memberOf.getValue("viewer"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM email_outbox WHERE member_id = ANY(?::uuid[])", (memberOf.values + kid).toTypedArray())
                tx.execute("DELETE FROM teams WHERE id IN (?, ?)", team, otherTeam)
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", (memberOf.values + kid).toTypedArray())
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { checkContract(); module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database), notifier = notifier) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, handle: String?, body: String? = null,
        withCsrf: String? = csrf): HttpResponse = client.request(path) {
        this.method = method
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        if (method != HttpMethod.Get) { header(HttpHeaders.Origin, origin); if (withCsrf != null) header("X-CSRF-Token", withCsrf) }
        if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private val start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(6).withHour(10).withMinute(0).withSecond(0).withNano(0)

    private fun event(team: UUID = this.team): UUID = UUID.randomUUID().also {
        sql { db -> db.execute("""INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at)
                                  VALUES (?, ?, 'MATCH', 'Away', ?::timestamptz, ?::timestamptz)""",
            it, team, start.toString(), start.plusHours(3).toString()) }
    }

    private fun ride(seats: Int = 2, departs: OffsetDateTime = start.minusHours(1), point: String = "Station") =
        """{"direction":"BOTH","seats":$seats,"meetingPoint":"$point","departsAt":"$departs"}"""

    private suspend fun ApplicationTestBuilder.offer(event: UUID, handle: String, seats: Int = 2): String {
        val response = send(HttpMethod.Post, "/api/v1/events/$event/rides", handle, ride(seats))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json().getValue("id").jsonPrimitive.content
    }

    private fun seat(ride: String, member: UUID) = "/api/v1/rides/$ride/passengers/$member"

    @Test
    fun teamMembersAndGuardiansOfferRidesOthersCannot() = app {
        val event = event()
        val other = event(otherTeam)
        val path = "/api/v1/events/$event/rides"
        val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
        }
        expect("no session", send(HttpMethod.Post, path, null, ride()), HttpStatusCode.Unauthorized)
        expect("no CSRF", send(HttpMethod.Post, path, "driver", ride(), withCsrf = null), HttpStatusCode.Forbidden)
        expect("no member", send(HttpMethod.Post, path, "stranger", ride()), HttpStatusCode.Forbidden)
        expect("viewer outside the team", send(HttpMethod.Post, path, "viewer", ride()), HttpStatusCode.Forbidden)
        expect("other team", send(HttpMethod.Post, "/api/v1/events/$other/rides", "driver", ride()), HttpStatusCode.Forbidden)
        expect("unknown event", send(HttpMethod.Post, "/api/v1/events/${UUID.randomUUID()}/rides", "driver", ride()), HttpStatusCode.Forbidden)
        for ((label, body) in listOf("no seats" to ride(seats = 0), "too many seats" to ride(seats = 9),
            "after the event" to ride(departs = start.plusHours(4)), "two days before" to ride(departs = start.minusDays(2)),
            "blank point" to ride(point = " "), "direction" to ride().replace("BOTH", "ROUND"),
            "seats as text" to ride().replace("\"seats\":2", "\"seats\":\"2\""), "extra key" to ride().replace("}", ",\"phone\":\"1\"}"))) {
            expect(label, send(HttpMethod.Post, path, "driver", body), HttpStatusCode.BadRequest)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
        val id = offer(event, "driver")
        offer(event, "guardian")
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, path, "driver", ride()).status, "one offer per driver and event")
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, "/api/v1/rides/$id", "rider", ride(seats = 3)).status, "only the driver changes it")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/rides/$id", "driver", ride(seats = 3)).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Delete, "/api/v1/rides/$id", "viewer").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/rides/$id", "coach").status, "a team manager removes it")
        sql { it.execute("DELETE FROM team_events WHERE id IN (?, ?)", event, other) }
    }

    @Test
    fun bookingRespectsSeatsClosingAndWhoMayAct() = app {
        val event = event()
        val id = offer(event, "driver", seats = 1)
        val rider = memberOf.getValue("rider")
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, seat(id, rider), "guardian").status, "not for someone else")
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, seat(id, memberOf.getValue("outsider")), "outsider").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, seat(UUID.randomUUID().toString(), rider), "rider").status)
        val results = coroutineScope {
            listOf("rider" to rider, "guardian" to kid).map { (handle, member) -> async { send(HttpMethod.Put, seat(id, member), handle) } }.awaitAll()
        }
        assertEquals(setOf(HttpStatusCode.NoContent, HttpStatusCode.Conflict), results.map { it.status }.toSet(), "one seat, one winner")
        assertTrue("\"full\"" in results.single { it.status == HttpStatusCode.Conflict }.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/rides/$id", "driver", ride(seats = 1).replace("BOTH", "THERE")).status,
            "keeping as many seats as bookings is fine")
        val holder = if (results[0].status == HttpStatusCode.NoContent) "rider" to rider else "guardian" to kid
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, seat(id, holder.second), holder.first).status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, seat(id, holder.second), holder.first).status, "cancelling twice")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, seat(id, rider), "rider").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/rides/$id", "driver", ride(seats = 2)).status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, seat(id, kid), "guardian").status)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Put, "/api/v1/rides/$id", "driver", ride(seats = 1)).status,
            "fewer seats than bookings")
        sql { it.execute("UPDATE event_rides SET departs_at = now() - INTERVAL '1 minute' WHERE id = ?::uuid", id) }
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Delete, seat(id, rider), "rider").status, "closed after departure")
        sql { it.execute("DELETE FROM team_events WHERE id = ?", event) }
    }

    @Test
    fun phonesGoOnlyBetweenADriverAndThePeopleTheyCarry() = app {
        val event = event()
        val id = offer(event, "driver", seats = 3)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, seat(id, kid), "guardian").status)
        val path = "/api/v1/events/$event/rides"
        fun JsonObject.ride() = getValue("items").jsonArray.single().jsonObject

        val asDriver = send(HttpMethod.Get, path, "driver").json()
        assertTrue(asDriver.getValue("canOffer").jsonPrimitive.boolean)
        val mine = asDriver.ride()
        assertEquals(setOf("id", "driver", "mine", "direction", "seats", "meetingPoint", "departsAt", "driverPhone", "passengers", "booked"), mine.keys)
        assertTrue(mine.getValue("mine").jsonPrimitive.boolean)
        val passenger = mine.getValue("passengers").jsonArray.single().jsonObject
        assertEquals("Kiddo R.", passenger.getValue("displayName").jsonPrimitive.content)
        assertEquals("+41000000072", passenger.getValue("phone").jsonPrimitive.content, "the driver sees the booking guardian's phone")

        val asGuardian = send(HttpMethod.Get, path, "guardian").json().ride()
        assertEquals("+41000000070", asGuardian.getValue("driverPhone").jsonPrimitive.content, "the guardian sees the driver's phone")
        assertEquals(listOf(kid.toString()), asGuardian.getValue("booked").jsonArray.map { it.jsonPrimitive.content })
        assertNull(asGuardian.getValue("passengers").jsonArray.single().jsonObject.getValue("phone").jsonPrimitive.contentOrNull)

        for (handle in listOf("rider", "viewer", "coach")) {
            val other = send(HttpMethod.Get, path, handle).json().ride()
            assertNull(other.getValue("driverPhone").jsonPrimitive.contentOrNull, "$handle sees no driver phone")
            assertTrue(other.getValue("passengers").jsonArray.all { it.jsonObject.getValue("phone").jsonPrimitive.contentOrNull == null },
                "$handle sees no passenger phone")
            assertEquals("driver Ride", other.getValue("driver").jsonPrimitive.content)
        }
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, path, "outsider").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, path, "stranger").status)

        // Leaving the team ends the bookings and the driver's offer.
        sql { it.execute("DELETE FROM member_team_assignments WHERE team_id = ? AND member_id = ?", team, kid) }
        assertTrue(send(HttpMethod.Get, path, "driver").json().ride().getValue("passengers").jsonArray.isEmpty())
        sql { it.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, kid) }
        sql { it.execute("DELETE FROM member_team_assignments WHERE team_id = ? AND member_id = ?", team, memberOf.getValue("driver")) }
        assertTrue(send(HttpMethod.Get, path, "rider").json().getValue("items").jsonArray.isEmpty())
        sql { it.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, memberOf.getValue("driver")) }
        sql { it.execute("DELETE FROM team_events WHERE id = ?", event) }
    }

    @Test
    fun aFormerGuardiansOfferAndPhoneDisappear() = app {
        // Security review 2026-11-26: the guardian drives for the kid's team without being in it.
        val rider = memberOf.getValue("rider")
        val event = event()
        val guardianRide = offer(event, "guardian")
        val driverRide = offer(event, "driver")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, seat(guardianRide, rider), "rider").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, seat(driverRide, kid), "guardian").status)
        val path = "/api/v1/events/$event/rides"
        suspend fun rides(handle: String) = send(HttpMethod.Get, path, handle).json().getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(2, rides("rider").size)

        sql { it.execute("DELETE FROM member_guardianships WHERE minor_member_id = ? AND guardian_member_id = ?", kid, memberOf.getValue("guardian")) }
        val seen = rides("rider")
        assertEquals(listOf(driverRide), seen.map { it.getValue("id").jsonPrimitive.content }, "the former guardian's offer is hidden")
        assertTrue(seen.none { it.getValue("driverPhone").jsonPrimitive.contentOrNull == "+41000000072" })
        val carried = rides("driver").single().getValue("passengers").jsonArray.single().jsonObject
        assertNull(carried.getValue("phone").jsonPrimitive.contentOrNull, "the driver no longer sees the former guardian's phone")
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, "/api/v1/rides/$guardianRide", "guardian", ride()).status,
            "the former guardian cannot change the offer")
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, seat(guardianRide, rider), "rider").status, "nor can anyone book it")

        sql { it.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian")) }
        sql { it.execute("DELETE FROM team_events WHERE id = ?", event) }
    }

    @Test
    fun aRideRequestIsSeenLikeTheRidesAndEndsWithABookedSeat() = app {
        val event = event()
        val path = "/api/v1/events/$event/rides"
        fun request(member: UUID) = "/api/v1/events/$event/ride-requests/$member"
        val id = offer(event, "driver")

        // A guardian asks for the child; the driver gets one message without a name, only for the first request.
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, request(kid), "guardian", """{"direction":"THERE"}""").status)
        val mails = sql { it.fetch("SELECT subject, body FROM email_outbox WHERE member_id = ?", memberOf.getValue("driver")) }
        assertEquals(1, mails.size)
        assertTrue(mails.single().get("subject", String::class.java).contains("Mitfahrt gesucht"))
        assertFalse(mails.single().get("body", String::class.java).contains("Kiddo"), "the message names nobody")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, request(memberOf.getValue("rider")), "rider", """{"direction":"BACK"}""").status)
        assertEquals(1, sql { (it.fetchValue("SELECT count(*) FROM email_outbox WHERE member_id = ?", memberOf.getValue("driver")) as Number).toInt() })

        // Everyone who sees the rides sees the requests, by display name, with the member ID only for their own people.
        for (handle in listOf("driver", "coach", "viewer", "rider", "guardian")) {
            val requests = send(HttpMethod.Get, path, handle).json().getValue("requests").jsonArray.map { it.jsonObject }
            assertEquals(listOf("Kiddo R." to "THERE", "rider Ride" to "BACK"),
                requests.map { it.getValue("displayName").jsonPrimitive.content to it.getValue("direction").jsonPrimitive.content }, handle)
            assertEquals(setOf("displayName", "direction", "memberId"), requests.first().keys)
            val own = requests.mapNotNull { it.getValue("memberId").jsonPrimitive.contentOrNull }
            assertEquals(when (handle) { "guardian" -> listOf(kid.toString()); "rider" -> listOf(memberOf.getValue("rider").toString()); else -> emptyList() }, own, handle)
        }

        // Only the person or their guardian asks; bad input is 400; a cancelled event is closed.
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, request(kid), "rider", """{"direction":"THERE"}""").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, request(memberOf.getValue("outsider")), "outsider", """{"direction":"THERE"}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, request(kid), "guardian", """{"direction":"UP"}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, request(kid), "guardian", """{"direction":"THERE","seats":1}""").status)

        // Booking a seat on a ride that covers the direction ends the request; withdrawing works until the start.
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, seat(id, kid), "guardian").status)
        val left = send(HttpMethod.Get, path, "driver").json().getValue("requests").jsonArray
        assertEquals(listOf("rider Ride"), left.map { it.jsonObject.getValue("displayName").jsonPrimitive.content })
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, request(memberOf.getValue("rider")), "rider").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, request(memberOf.getValue("rider")), "rider").status)
        assertTrue(send(HttpMethod.Get, path, "driver").json().getValue("requests").jsonArray.isEmpty())
        sql { it.execute("UPDATE team_events SET cancelled = TRUE WHERE id = ?", event) }
        val closed = send(HttpMethod.Put, request(memberOf.getValue("rider")), "rider", """{"direction":"BOTH"}""")
        assertEquals(HttpStatusCode.Conflict, closed.status)
        assertTrue(closed.bodyAsText().contains("closed"))
        sql { it.execute("DELETE FROM team_events WHERE id = ?", event) }
    }

    @Test
    fun aRideRequestEndsWithTheTeamAndTheEventAndIsExported() = app {
        val event = event()
        val rider = memberOf.getValue("rider")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$event/ride-requests/$rider", "rider", """{"direction":"BACK"}""").status)
        fun count() = sql { (it.fetchValue("SELECT count(*) FROM ride_requests WHERE event_id = ?", event) as Number).toInt() }

        // The member export lists it.
        val exported = sql { MemberExport(it).exportAsOperator(rider) }!!.getValue("rideRequests").jsonArray.single().jsonObject
        assertEquals(event.toString(), exported.getValue("eventId").jsonPrimitive.content)
        assertEquals("BACK", exported.getValue("direction").jsonPrimitive.content)
        assertEquals(setOf("eventId", "direction", "requestedAt"), exported.keys)

        // Leaving the team ends it (trigger); coming back does not bring it back.
        sql { it.execute("DELETE FROM member_team_assignments WHERE team_id = ? AND member_id = ?", team, rider) }
        assertEquals(0, count())
        sql { it.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, rider) }
        assertEquals(0, count())

        // The retention job removes requests of events that have ended, and only those.
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$event/ride-requests/$rider", "rider", """{"direction":"BOTH"}""").status)
        sql { RetentionCleanup.pruneBatch(it) }
        assertEquals(1, count(), "the event has not ended")
        sql { it.execute("UPDATE team_events SET starts_at = now() - INTERVAL '3 hours', ends_at = now() - INTERVAL '1 hour' WHERE id = ?", event) }
        assertTrue(sql { RetentionCleanup.pruneBatch(it) }.rideRequests >= 1)
        assertEquals(0, count())
        sql { it.execute("DELETE FROM team_events WHERE id = ?", event) }
    }
}
