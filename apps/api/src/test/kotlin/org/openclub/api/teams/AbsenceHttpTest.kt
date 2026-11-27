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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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
import org.openclub.api.module
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Absences (OpenAPI 1.20.0): who enters them, the date rules, and the NO they imply where nobody answered. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbsenceHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val team = UUID.randomUUID()
    private val kid = UUID.randomUUID()
    private val today = LocalDate.now(ZoneId.of("Europe/Zurich"))
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
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Absence Club', ?)", club, "absence-$club")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Absence ${team.toString().take(8)}")
            // player plays; guardian has the kid in the team; coach views the team; other is a member; stranger none.
            for (handle in listOf("player", "guardian", "coach", "other", "stranger")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "absence-$account")
                if (handle == "stranger") continue
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Absence', DATE '1980-01-01', 'a@example.invalid', '+41000000060')""", it, account, handle)
                }
            }
            db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, memberOf.getValue("player"))
            db.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Absence', CURRENT_DATE - 3650)", kid)
            db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, kid)
            db.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
            db.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'VIEW_TEAMS')", team, memberOf.getValue("coach"))
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
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database)) }
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
    private fun absence(member: UUID, from: LocalDate, to: LocalDate) = """{"memberId":"$member","from":"$from","to":"$to"}"""

    private suspend fun ApplicationTestBuilder.create(handle: String, member: UUID, from: LocalDate, to: LocalDate): String {
        val response = send(HttpMethod.Post, "/api/v1/me/absences", handle, absence(member, from, to))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json().getValue("id").jsonPrimitive.content
    }

    @Test
    fun membersAndGuardiansEnterAndDeleteAbsencesWithinTheRules() = app {
        val player = memberOf.getValue("player")
        val id = create("player", player, today.plusDays(3), today.plusDays(10))
        val kidAbsence = create("guardian", kid, today, today)
        val listing = send(HttpMethod.Get, "/api/v1/me/absences", "guardian").json()
        assertEquals(setOf(kid.toString(), memberOf.getValue("guardian").toString()),
            listing.getValue("people").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
        val item = listing.getValue("items").jsonArray.single().jsonObject
        assertEquals(setOf("id", "memberId", "firstName", "from", "to", "note"), item.keys)
        assertEquals("Kiddo", item.getValue("firstName").jsonPrimitive.content)
        assertTrue(send(HttpMethod.Get, "/api/v1/me/absences", "player").json().getValue("items").jsonArray.single()
            .jsonObject.getValue("id").jsonPrimitive.content == id, "each sees only their own people's absences")

        val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
        }
        val path = "/api/v1/me/absences"
        expect("no session", send(HttpMethod.Get, path, null), HttpStatusCode.Unauthorized)
        expect("no member", send(HttpMethod.Get, path, "stranger"), HttpStatusCode.Forbidden)
        expect("no CSRF", send(HttpMethod.Post, path, "player", absence(player, today, today), withCsrf = null), HttpStatusCode.Forbidden)
        expect("for someone else", send(HttpMethod.Post, path, "other", absence(player, today, today)), HttpStatusCode.Forbidden)
        expect("coach for a player", send(HttpMethod.Post, path, "coach", absence(kid, today, today)), HttpStatusCode.Forbidden)
        expect("in the past", send(HttpMethod.Post, path, "player", absence(player, today.minusDays(1), today)), HttpStatusCode.BadRequest)
        expect("reversed", send(HttpMethod.Post, path, "player", absence(player, today.plusDays(2), today.plusDays(1))), HttpStatusCode.BadRequest)
        expect("over a year", send(HttpMethod.Post, path, "player", absence(player, today, today.plusDays(366))), HttpStatusCode.BadRequest)
        expect("too far ahead", send(HttpMethod.Post, path, "player", absence(player, today.plusDays(700), today.plusDays(740))), HttpStatusCode.BadRequest)
        expect("bad date", send(HttpMethod.Post, path, "player", """{"memberId":"$player","from":"2026-02-30","to":"2026-03-01"}"""), HttpStatusCode.BadRequest)
        expect("extra key", send(HttpMethod.Post, path, "player", """{"memberId":"$player","from":"$today","to":"$today","reason":"ill"}"""), HttpStatusCode.BadRequest)
        expect("delete someone else's", send(HttpMethod.Delete, "/api/v1/absences/$kidAbsence", "player"), HttpStatusCode.Forbidden)
        expect("delete unknown", send(HttpMethod.Delete, "/api/v1/absences/${UUID.randomUUID()}", "player"), HttpStatusCode.Forbidden)
        expect("delete bad id", send(HttpMethod.Delete, "/api/v1/absences/nope", "player"), HttpStatusCode.BadRequest)
        expect("guardian deletes", send(HttpMethod.Delete, "/api/v1/absences/$kidAbsence", "guardian"), HttpStatusCode.NoContent)
        expect("player deletes", send(HttpMethod.Delete, "/api/v1/absences/$id", "player"), HttpStatusCode.NoContent)
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
        assertEquals(0, sql { (it.fetchValue("SELECT count(*) FROM member_absences WHERE member_id IN (?, ?)", player, kid) as Number).toInt() })
    }

    @Test
    fun anAbsenceAnswersNoWhereNobodyAnsweredAndAnExplicitAnswerWins() = app {
        val player = memberOf.getValue("player")
        val day = today.plusDays(20)
        val starts = day.atTime(18, 0).atZone(ZoneId.of("Europe/Zurich")).toOffsetDateTime()
        val inside = UUID.randomUUID()
        val answered = UUID.randomUUID()
        val outside = UUID.randomUUID()
        sql { db ->
            for ((id, at) in listOf(inside to starts, answered to starts.plusHours(1), outside to starts.plusDays(5)))
                db.execute("""INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at)
                              VALUES (?, ?, 'TRAINING', 'Absence', ?::timestamptz, ?::timestamptz)""", id, team, at.toString(), at.plusHours(1).toString())
            db.execute("INSERT INTO event_attendance (event_id, member_id, response) VALUES (?, ?, 'YES')", answered, player)
        }
        try {
            val absenceId = create("player", player, day, day.plusDays(1))
            fun response(event: UUID) = sql {
                it.fetchValue("SELECT response FROM effective_attendance WHERE event_id = ? AND member_id = ?", event, player)?.toString()
            }
            assertEquals("NO", response(inside))
            assertEquals("YES", response(answered), "an explicit answer wins")
            assertNull(response(outside))
            assertNull(sql { it.fetchValue("SELECT response FROM effective_attendance WHERE event_id = ? AND member_id = ?", inside, kid) },
                "only the absent member")
            val answers = send(HttpMethod.Get, "/api/v1/events/$inside/attendance", "coach").json()
            assertEquals(1, answers.getValue("counts").jsonObject.getValue("no").jsonPrimitive.int, "coaches see a plain No")
            assertEquals("NO", answers.getValue("items").jsonArray.map { it.jsonObject }
                .single { it.getValue("displayName").jsonPrimitive.content == "player Absence" }.getValue("response").jsonPrimitive.content)
            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/absences/$absenceId", "player").status)
            assertNull(response(inside), "deleting the absence removes the implied No")
        } finally {
            sql { it.execute("DELETE FROM team_events WHERE id IN (?, ?, ?)", inside, answered, outside) }
        }
    }
}
