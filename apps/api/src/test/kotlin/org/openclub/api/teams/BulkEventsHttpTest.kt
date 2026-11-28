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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import org.openclub.api.notifications.MailConfig
import org.openclub.api.notifications.Notifier
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Changing several events at once (OpenAPI 1.29.0): rights for every team, the selection, the summary email. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BulkEventsHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val team = UUID.randomUUID()
    private val otherTeam = UUID.randomUUID()
    private val swiss = ZoneId.of("Europe/Zurich")
    private val day: LocalDate = LocalDate.now(swiss).plusDays(10)
    private val trainings = listOf(UUID.randomUUID(), UUID.randomUUID())
    private val match = UUID.randomUUID()
    private val past = UUID.randomUUID()
    private val foreign = UUID.randomUUID()
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
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Bulk Club', ?)", club, "bulk-$club")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Bulk ${team.toString().take(8)}")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", otherTeam, "Elsewhere ${otherTeam.toString().take(8)}")
            for (handle in listOf("coach", "player", "viewer")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "bulk-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Bulk', DATE '1980-01-01', 'b@example.invalid', '+41000000090')""", it, account, handle)
                }
            }
            db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, memberOf.getValue("player"))
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'MANAGE_TEAMS')", team, memberOf.getValue("coach"))
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'VIEW_TEAMS')", team, memberOf.getValue("viewer"))
            fun event(id: UUID, teamId: UUID, kind: String, starts: ZonedDateTime) = db.execute(
                """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, location)
                   VALUES (?, ?, ?, 'Bulk event', ?::timestamptz, ?::timestamptz, 'Halle A')""",
                id, teamId, kind, starts.toOffsetDateTime().toString(), starts.plusHours(1).toOffsetDateTime().toString())
            event(trainings[0], team, "TRAINING", day.atTime(18, 0).atZone(swiss))
            event(trainings[1], team, "TRAINING", day.plusDays(2).atTime(18, 0).atZone(swiss))
            event(match, team, "MATCH", day.plusDays(1).atTime(10, 0).atZone(swiss))
            event(past, team, "TRAINING", LocalDate.now(swiss).minusDays(2).atTime(18, 0).atZone(swiss))
            event(foreign, otherTeam, "TRAINING", day.atTime(18, 0).atZone(swiss))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM email_outbox WHERE member_id = ANY(?::uuid[])", memberOf.values.toTypedArray())
                tx.execute("DELETE FROM teams WHERE id = ANY(?::uuid[])", arrayOf(team, otherTeam))
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", memberOf.values.toTypedArray())
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database), notifier = notifier) }
        block()
    }

    private suspend fun ApplicationTestBuilder.bulk(handle: String?, body: String): HttpResponse = client.request("/api/v1/events/bulk") {
        method = HttpMethod.Post
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
        header(HttpHeaders.ContentType, "application/json"); setBody(body)
    }

    private fun body(action: String, preview: Boolean = false, location: String? = null, teams: List<UUID> = listOf(team),
        kinds: String = "[\"TRAINING\"]", from: LocalDate = LocalDate.now(swiss).minusDays(5), to: LocalDate = day.plusDays(5)) =
        """{"teamIds":[${teams.joinToString(",") { "\"$it\"" }}],"from":"$from","to":"$to","kinds":$kinds,"action":"$action",""" +
            """"location":${location?.let { "\"$it\"" } ?: "null"},"preview":$preview}"""

    private suspend fun HttpResponse.counts(): Pair<Int, Int> {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        val json = Json.parseToJsonElement(bodyAsText()).jsonObject
        return json.getValue("matched").jsonPrimitive.int to json.getValue("changed").jsonPrimitive.int
    }

    private fun cancelled(id: UUID) = sql { it.fetchValue("SELECT cancelled FROM team_events WHERE id = ?", id) as Boolean }

    @Test
    @Order(1)
    fun aPreviewCountsAndChangesNothing() = app {
        assertEquals(2 to 0, bulk("coach", body("CANCEL", preview = true)).counts())
        assertTrue(trainings.none(::cancelled))
    }

    @Test
    @Order(2)
    fun cancellingChangesOnlyTheChosenFutureEventsAndSendsOneSummary() = app {
        assertEquals(2 to 2, bulk("coach", body("CANCEL")).counts())
        assertTrue(trainings.all(::cancelled))
        assertTrue(!cancelled(match) && !cancelled(past) && !cancelled(foreign), "match, past and other team untouched")
        val mails = sql { it.fetch("SELECT kind, subject FROM email_outbox WHERE member_id = ?", memberOf.getValue("player")) }
        assertEquals(1, mails.size, "one summary, not one per event")
        assertEquals("EVENT_CANCELLED", mails.single().get("kind", String::class.java))
        assertTrue(mails.single().get("subject", String::class.java).contains("2 ×"))
        assertEquals(0 to 0, bulk("coach", body("CANCEL")).counts(), "already cancelled events are not changed again")
        assertEquals(2 to 2, bulk("coach", body("REINSTATE")).counts())
        assertTrue(trainings.none(::cancelled))
    }

    @Test
    @Order(3)
    fun aNewPlaceForTrainingsAndMatches() = app {
        assertEquals(3 to 3, bulk("coach", body("SET_LOCATION", location = "Halle B", kinds = "[\"TRAINING\",\"MATCH\"]")).counts())
        assertEquals("Halle B", sql { it.fetchValue("SELECT location FROM team_events WHERE id = ?", match) })
        assertEquals("Halle A", sql { it.fetchValue("SELECT location FROM team_events WHERE id = ?", past) })
        assertEquals(0 to 0, bulk("coach", body("SET_LOCATION", location = "Halle B", kinds = "[\"TRAINING\",\"MATCH\"]")).counts())
    }

    @Test
    @Order(4)
    fun everyTeamNeedsTheRightAndTheInputIsChecked() = app {
        val failures = mutableListOf<String>()
        suspend fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status} ${response.bodyAsText()}"
        }
        expect("signed out", bulk(null, body("CANCEL")), HttpStatusCode.Unauthorized)
        expect("viewer", bulk("viewer", body("CANCEL")), HttpStatusCode.Forbidden)
        expect("player", bulk("player", body("CANCEL")), HttpStatusCode.Forbidden)
        expect("one team without the right", bulk("coach", body("CANCEL", teams = listOf(team, otherTeam))), HttpStatusCode.Forbidden)
        expect("unknown team, team-scoped", bulk("coach", body("CANCEL", teams = listOf(UUID.randomUUID()))), HttpStatusCode.Forbidden)
        expect("place with cancel", bulk("coach", body("CANCEL", location = "x")), HttpStatusCode.BadRequest)
        expect("no kinds", bulk("coach", body("CANCEL", kinds = "[]")), HttpStatusCode.BadRequest)
        expect("unknown kind", bulk("coach", body("CANCEL", kinds = "[\"PARTY\"]")), HttpStatusCode.BadRequest)
        expect("from after to", bulk("coach", body("CANCEL", from = day, to = day.minusDays(1))), HttpStatusCode.BadRequest)
        expect("over a year", bulk("coach", body("CANCEL", from = day, to = day.plusDays(400))), HttpStatusCode.BadRequest)
        expect("no teams", bulk("coach", body("CANCEL", teams = emptyList())), HttpStatusCode.BadRequest)
        expect("preview as text", bulk("coach", body("CANCEL").replace("\"preview\":false", "\"preview\":\"no\"")), HttpStatusCode.BadRequest)
        expect("unknown action", bulk("coach", body("DELETE")), HttpStatusCode.BadRequest)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertTrue(!cancelled(foreign) && trainings.none(::cancelled), "nothing changed by refused requests")
    }

    @Test
    @Order(5)
    fun newTimesKeepEachDayMoveTheDeadlineAndRefuseADoubleBooking() = app {
        fun times(start: String, end: String, action: String = "SET_TIMES") =
            body(action).dropLast(1) + ""","startTime":"$start","endTime":"$end"}"""
        fun local(id: UUID, column: String) = sql {
            it.fetchValue("SELECT to_char($column AT TIME ZONE 'Europe/Zurich', 'YYYY-MM-DD HH24:MI') FROM team_events WHERE id = ?", id) as String
        }
        sql { it.execute("UPDATE team_events SET answers_until = starts_at - INTERVAL '1 day' WHERE id = ?", trainings[1]) }
        assertEquals(2 to 0, bulk("coach", times("18:30", "20:00").replace("\"preview\":false", "\"preview\":true")).counts())
        assertEquals(2 to 2, bulk("coach", times("18:30", "20:00")).counts())
        assertEquals("$day 18:30", local(trainings[0], "starts_at"))
        assertEquals("$day 20:00", local(trainings[0], "ends_at"))
        assertEquals("${day.plusDays(1)} 18:30", local(trainings[1], "answers_until"), "the deadline keeps its lead")
        assertTrue(local(past, "starts_at").endsWith("18:00"), "a past event keeps its time")
        assertTrue(local(match, "starts_at").endsWith("10:00"), "another kind keeps its time")
        assertEquals(0 to 0, bulk("coach", times("18:30", "20:00")).counts())

        for ((label, request) in listOf("end before start" to times("20:00", "18:30"), "no times" to body("SET_TIMES"),
                "times with cancel" to times("18:30", "20:00", "CANCEL"), "not a time" to times("25:00", "26:00"))) {
            assertEquals(HttpStatusCode.BadRequest, bulk("coach", request).status, label)
        }

        // A field booked by another team at 21:00 refuses the whole change.
        val venue = UUID.randomUUID()
        sql { db ->
            db.execute("INSERT INTO venues (id, name) VALUES (?, ?)", venue, "Bulk hall ${venue.toString().take(8)}")
            db.execute("UPDATE team_events SET venue_id = ? WHERE id = ?", venue, trainings[0])
            db.execute("""INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, venue_id)
                          VALUES (?, ?, 'TRAINING', 'Other', ?::timestamptz, ?::timestamptz, ?)""", UUID.randomUUID(), otherTeam,
                day.atTime(21, 0).atZone(swiss).toOffsetDateTime().toString(), day.atTime(22, 0).atZone(swiss).toOffsetDateTime().toString(), venue)
        }
        val taken = bulk("coach", times("21:00", "22:00"))
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertTrue(taken.bodyAsText().contains("venue_taken"))
        assertEquals("$day 18:30", local(trainings[0], "starts_at"), "nothing changed")
        sql { db ->
            db.execute("DELETE FROM team_events WHERE venue_id = ? AND team_id = ?", venue, otherTeam)
            db.execute("DELETE FROM venues WHERE id = ?", venue)
        }
    }
}
