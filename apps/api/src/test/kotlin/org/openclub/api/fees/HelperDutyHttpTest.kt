package org.openclub.api.fees

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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
import org.openclub.api.checkContract

/** Helper duty (OpenAPI 1.32.0): who owes shifts, what counts, and who sees the overview. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HelperDutyHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val team = UUID.randomUUID()
    private val kid = UUID.randomUUID()
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    /** The season of a club whose season starts in [month]: as the server computes it, in Swiss dates. */
    private fun seasonFrom(month: Int): LocalDate {
        val today = LocalDate.now(ZoneId.of("Europe/Zurich"))
        return LocalDate.of(if (today.monthValue >= month) today.year else today.year - 1, month, 1)
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        // A season month a little before today, so the season started at least a few days ago.
        val month = LocalDate.now(ZoneId.of("Europe/Zurich")).minusDays(40).monthValue
        val from = seasonFrom(month)
        sql { db ->
            db.execute("INSERT INTO clubs (id, name, slug, season_start_month) VALUES (?, 'Duty Club', ?, ?)", club, "duty-$club", month)
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Duty ${team.toString().take(8)}")
            for (handle in listOf("treasurer", "player", "guardian", "outsider")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "duty-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Duty', DATE '1980-01-01', 'd@example.invalid', '+41000000120')""",
                        it, account, handle.replaceFirstChar(Char::uppercase))
                }
            }
            db.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Duty', CURRENT_DATE - 3650)", kid)
            for (member in listOf(memberOf.getValue("player"), kid)) db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, member)
            db.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
            db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_FEES')", memberOf.getValue("treasurer"))
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val inSeason = from.plusDays(2).atTime(10, 0).atZone(ZoneId.of("Europe/Zurich")).toOffsetDateTime()
            // (event start, cancelled, volunteer): two count, the rest do not.
            val shifts = listOf(
                Triple(inSeason, false, memberOf.getValue("player")),
                Triple(inSeason, false, kid),
                Triple(now.plusDays(5), false, memberOf.getValue("player")),
                Triple(inSeason, true, memberOf.getValue("player")),
                Triple(from.minusDays(10).atTime(10, 0).atZone(ZoneId.of("Europe/Zurich")).toOffsetDateTime(), false, memberOf.getValue("player")),
            )
            for ((at, cancelled, volunteer) in shifts) {
                val event = UUID.randomUUID()
                val shift = UUID.randomUUID()
                db.execute("""INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, cancelled)
                              VALUES (?, ?, 'MATCH', 'Duty match', ?::timestamptz, ?::timestamptz, ?)""",
                    event, team, at.toString(), at.plusHours(2).toString(), cancelled)
                db.execute("""INSERT INTO event_shifts (id, event_id, title, starts_at, ends_at, needed)
                              VALUES (?, ?, 'Bar', ?::timestamptz, ?::timestamptz, 2)""", shift, event, at.toString(), at.plusHours(2).toString())
                db.execute("INSERT INTO shift_volunteers (shift_id, member_id) VALUES (?, ?)", shift, volunteer)
            }
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM teams WHERE id = ?", team)
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", (memberOf.values + kid).toTypedArray())
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { checkContract(); module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, handle: String?, body: String? = null): HttpResponse =
        client.request(path) {
            this.method = method
            if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            if (method != HttpMethod.Get) { header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf) }
            if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
        }

    private suspend fun HttpResponse.json(): JsonObject {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return Json.parseToJsonElement(bodyAsText()).jsonObject
    }

    @Test
    @Order(1)
    fun onlyTreasurersSetTheQuota() = app {
        val month = sql { it.fetchValue("SELECT season_start_month FROM clubs WHERE id = ?", club) as Int }
        val body = """{"quota":3,"seasonStartMonth":$month}"""
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, "/api/v1/club/helper-duty", "player", body).status)
        for (bad in listOf("""{"quota":51,"seasonStartMonth":8}""", """{"quota":3,"seasonStartMonth":13}""",
                """{"quota":"3","seasonStartMonth":8}""", """{"quota":3}""")) {
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, "/api/v1/club/helper-duty", "treasurer", bad).status, bad)
        }
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/club/helper-duty", "treasurer", body).status)
    }

    @Test
    @Order(2)
    fun theOverviewCountsStartedShiftsOfThisSeasonForParentsToo() = app {
        val overview = send(HttpMethod.Get, "/api/v1/helper-duty", "treasurer").json()
        assertEquals(3, overview.getValue("quota").jsonPrimitive.int)
        val rows = overview.getValue("items").jsonArray.map { it.jsonObject }
            .associate { it.getValue("displayName").jsonPrimitive.content to (it.getValue("done").jsonPrimitive.int to it.getValue("owed").jsonPrimitive.int) }
        assertEquals(mapOf("Player Duty" to (1 to 2), "Guardian Duty" to (1 to 2)), rows,
            "the outsider and the treasurer are in no team; the child is not liable; future, cancelled and earlier shifts do not count")
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/helper-duty", "player").status)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/v1/helper-duty", null).status)
    }

    @Test
    @Order(3)
    fun eachPersonSeesTheirOwnCount() = app {
        val mine = send(HttpMethod.Get, "/api/v1/me/helper-duty", "player").json()
        assertEquals(3, mine.getValue("quota").jsonPrimitive.int)
        assertEquals(1, mine.getValue("done").jsonPrimitive.int)
        assertEquals(setOf("quota", "done", "seasonFrom", "seasonTo"), mine.keys)
        assertEquals(1, send(HttpMethod.Get, "/api/v1/me/helper-duty", "guardian").json().getValue("done").jsonPrimitive.int)
        assertEquals(0, send(HttpMethod.Get, "/api/v1/me/helper-duty", "outsider").json().getValue("quota").jsonPrimitive.int,
            "no quota for someone in no team")
        assertTrue(send(HttpMethod.Get, "/api/v1/me/helper-duty", null).status == HttpStatusCode.Unauthorized)
    }
}
