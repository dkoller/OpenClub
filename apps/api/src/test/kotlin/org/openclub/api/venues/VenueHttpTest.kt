package org.openclub.api.venues

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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.module
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Fields and halls (OpenAPI 1.30.0): who manages them, what everyone sees, and no double booking. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VenueHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val team = UUID.randomUUID()
    private val otherTeam = UUID.randomUUID()
    private val start: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).plusDays(20).withHour(17).withMinute(0).withSecond(0).withNano(0)
    private var venue = ""
    private var firstEvent = ""
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
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Venue Club', ?)", club, "venue-$club")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Venue A ${team.toString().take(8)}")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", otherTeam, "Venue B ${otherTeam.toString().take(8)}")
            for (handle in listOf("keeper", "coach", "rival", "member")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "venue-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Venue', DATE '1980-01-01', 'v@example.invalid', '+41000000100')""", it, account, handle)
                }
            }
            db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_VENUES')", memberOf.getValue("keeper"))
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'MANAGE_TEAMS')", team, memberOf.getValue("coach"))
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'MANAGE_TEAMS')", otherTeam, memberOf.getValue("rival"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM teams WHERE id = ANY(?::uuid[])", arrayOf(team, otherTeam))
                tx.execute("DELETE FROM venues WHERE name LIKE 'Test hall%' OR name LIKE 'test hall%'")
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", memberOf.values.toTypedArray())
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, handle: String?, body: String? = null): HttpResponse =
        client.request(path) {
            this.method = method
            if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            if (method != HttpMethod.Get) { header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf) }
            if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun event(at: OffsetDateTime, venueId: String?, title: String = "Training", extra: String = "") =
        """{"kind":"TRAINING","title":"$title","startsAt":"$at","endsAt":"${at.plusMinutes(90)}","venueId":${venueId?.let { "\"$it\"" } ?: "null"}$extra}"""

    @Test
    @Order(1)
    fun onlyVenueManagersChangeTheListEveryMemberReadsIt() = app {
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/v1/venues", "coach", """{"name":"Test hall 1"}""").status)
        val created = send(HttpMethod.Post, "/api/v1/venues", "keeper", """{"name":"Test hall 1"}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        venue = created.json().getValue("id").jsonPrimitive.content
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/v1/venues", "keeper", """{"name":"test hall 1"}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/v1/venues", "keeper", """{"name":"  "}""").status)
        val listing = send(HttpMethod.Get, "/api/v1/venues", "member").json()
        assertEquals(false, listing.getValue("canManage").jsonPrimitive.boolean)
        assertTrue(listing.getValue("items").jsonArray.any { it.jsonObject.getValue("id").jsonPrimitive.content == venue })
        assertEquals(true, send(HttpMethod.Get, "/api/v1/venues", "keeper").json().getValue("canManage").jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/v1/venues", null).status)
    }

    @Test
    @Order(2)
    fun aVenueCannotBeBookedTwiceAtTheSameTime() = app {
        val first = send(HttpMethod.Post, "/api/v1/teams/$team/events", "coach", event(start, venue))
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        firstEvent = first.json().getValue("ids").jsonArray.single().jsonPrimitive.content
        val clash = send(HttpMethod.Post, "/api/v1/teams/$otherTeam/events", "rival", event(start.plusMinutes(30), venue, "Clash"))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        val detail = clash.json()
        assertEquals("venue_taken", detail.getValue("code").jsonPrimitive.content)
        assertEquals("Training", detail.getValue("clash").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/v1/teams/$otherTeam/events", "rival",
            event(start.plusMinutes(90), venue, "Right after")).status, "touching ends do not overlap")
        // A weekly series where one copy clashes creates nothing.
        val tz = ""","repeatWeeklyUntil":"${start.minusWeeks(1).atZoneSameInstant(ZoneId.of("Europe/Zurich")).toLocalDate().plusWeeks(1)}","timeZone":"Europe/Zurich""""
        val series = send(HttpMethod.Post, "/api/v1/teams/$otherTeam/events", "rival", event(start.minusWeeks(1).plusMinutes(10), venue, "Series", tz))
        assertEquals(HttpStatusCode.Conflict, series.status, series.bodyAsText())
        assertEquals(0, sql { (it.fetchValue("SELECT count(*) FROM team_events WHERE title = 'Series' AND team_id = ?", otherTeam) as Number).toInt() })
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/v1/teams/$team/events", "coach",
            event(start.plusDays(1), UUID.randomUUID().toString())).status, "unknown venue")
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/v1/teams/$team/events", "coach",
            event(start.plusDays(1), null).replace("\"venueId\":null", "\"venueId\":5")).status)
    }

    @Test
    @Order(3)
    fun cancellingFreesTheVenueAndEventsShowIt() = app {
        val moved = event(start.minusMinutes(60), venue, "Clash again", ""","cancelled":false""")
        val rival = send(HttpMethod.Post, "/api/v1/teams/$otherTeam/events", "rival", event(start.plusDays(3), null, "Elsewhere"))
        val rivalId = rival.json().getValue("ids").jsonArray.single().jsonPrimitive.content
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Put, "/api/v1/events/$rivalId", "rival", moved).status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$firstEvent", "coach",
            event(start, venue, extra = ""","cancelled":true""")).status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$rivalId", "rival", moved).status)
        val schedule = send(HttpMethod.Get, "/api/v1/teams/$otherTeam/events?from=${start.minusDays(1)}&to=${start.plusDays(5)}".replace("+", "%2B"), "rival").json()
        val item = schedule.getValue("items").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == rivalId }
        assertEquals("Test hall 1", item.getValue("venueName").jsonPrimitive.content)
        // A cancelled event cannot be reinstated onto a taken slot.
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Put, "/api/v1/events/$firstEvent", "coach",
            event(start, venue, extra = ""","cancelled":false""")).status)
    }

    @Test
    @Order(4)
    fun everyMemberSeesTheBookingsWithoutNames() = app {
        val bookings = send(HttpMethod.Get, "/api/v1/venues/$venue/bookings?from=${start.minusDays(1)}&to=${start.plusDays(2)}".replace("+", "%2B"), "member")
        assertEquals(HttpStatusCode.OK, bookings.status, bookings.bodyAsText())
        val items = bookings.json().getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Clash again", "Right after"), items.map { it.getValue("title").jsonPrimitive.content })
        assertEquals(setOf("eventId", "teamName", "kind", "title", "startsAt", "endsAt"), items.first().keys)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "/api/v1/venues/${UUID.randomUUID()}/bookings", "member").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, "/api/v1/venues/$venue/bookings?from=x", "member").status)
    }

    @Test
    @Order(5)
    fun bulkMovesRespectTheVenueAndDeletingKeepsTheEvents() = app {
        val day = start.atZoneSameInstant(ZoneId.of("Europe/Zurich")).toLocalDate()
        fun bulk(venueId: String?, from: LocalDate, to: LocalDate) =
            """{"teamIds":["$team"],"from":"$from","to":"$to","kinds":["TRAINING"],"action":"SET_VENUE","location":null,"preview":false,"venueId":${venueId?.let { "\"$it\"" } ?: "null"}}"""
        val second = send(HttpMethod.Post, "/api/v1/teams/$team/events", "coach", event(start.plusDays(1), null, "Next day"))
        assertEquals(HttpStatusCode.Created, second.status)
        // The cancelled first event is left alone; the next day's training moves to the hall.
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/v1/events/bulk", "coach", bulk(venue, day, day.plusDays(1))).status)
        // Another team's training at that time cannot be moved onto it.
        send(HttpMethod.Post, "/api/v1/teams/$otherTeam/events", "rival", event(start.plusDays(1).plusMinutes(15), null, "Overlap"))
        val clash = send(HttpMethod.Post, "/api/v1/events/bulk", "rival",
            bulk(venue, day.plusDays(1), day.plusDays(1)).replace("\"$team\"", "\"$otherTeam\""))
        assertEquals(HttpStatusCode.Conflict, clash.status, clash.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/v1/events/bulk", "coach",
            bulk(null, day, day).replace("\"SET_VENUE\"", "\"CANCEL\"").replace("\"venueId\":null", "\"venueId\":\"$venue\"")).status)

        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/venues/$venue", "keeper", """{"name":"Test hall renamed"}""").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Delete, "/api/v1/venues/$venue", "coach").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/venues/$venue", "keeper").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Delete, "/api/v1/venues/$venue", "keeper").status)
        assertEquals(null, sql { it.fetchValue("SELECT venue_id FROM team_events WHERE id = ?::uuid", firstEvent) }, "the event stays, without the venue")
        assertTrue(sql { (it.fetchValue("SELECT count(*) FROM team_events WHERE team_id = ?", team) as Number).toInt() } >= 2, "events stay")
    }

    /** A second database connection with an open transaction, to race the API (two-connection test). */
    private fun <T> otherTransaction(block: (java.sql.Connection) -> T): Pair<java.sql.Connection, T> {
        val env = System.getenv()
        val connection = java.sql.DriverManager.getConnection(env.getValue("DB_URL"), env["DB_USER"], env["DB_PASSWORD"])
        connection.autoCommit = false
        return connection to block(connection)
    }

    @Test
    @Order(9)
    fun twoCoachesBookingTheSameHallAtTheSameMomentGetA409NotA503() = app {
        val at = start.plusDays(40)
        val hall = send(HttpMethod.Post, "/api/v1/venues", "keeper", """{"name":"Test hall race"}""").json().getValue("id").jsonPrimitive.content
        // Another coach's booking is in flight: inserted, not yet committed, so the API's clash check cannot see it.
        val (other, _) = otherTransaction { c ->
            c.prepareStatement("""INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, venue_id)
                                  VALUES (?, ?, 'TRAINING', 'In flight', ?::timestamptz, ?::timestamptz, ?::uuid)""").use {
                it.setObject(1, UUID.randomUUID()); it.setObject(2, otherTeam); it.setString(3, at.toString())
                it.setString(4, at.plusMinutes(90).toString()); it.setString(5, hall); it.executeUpdate()
            }
        }
        other.use { coroutineScope {
            val create = async { send(HttpMethod.Post, "/api/v1/teams/$team/events", "coach", event(at.plusMinutes(15), hall, "Racing")) }
            delay(1500)
            assertTrue(!create.isCompleted, "the insert waits for the other booking")
            other.commit()
            val response = create.await()
            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertTrue("venue_taken" in response.bodyAsText())
        } }
        assertEquals(0, sql { (it.fetchValue("SELECT count(*) FROM team_events WHERE title = 'Racing'") as Number).toInt() })
    }
}
