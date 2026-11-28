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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.MemberExport
import org.openclub.api.module
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Notes on answers and absences and the answer deadline (OpenAPI 1.39.0, ADR-0008 addendum 44): who sees a note, how
 * it is kept, replaced and removed, and that nobody answers after the deadline.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnswerNotesHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val team = UUID.randomUUID()
    private val zurich = ZoneId.of("Europe/Zurich")
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
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Notes Club', ?)", club, "notes-$club")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Notes ${team.toString().take(8)}")
            // player and away play; coach manages the team; mate is in the team without any grant.
            for (handle in listOf("player", "away", "coach", "mate")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "notes-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Notes', DATE '1980-01-01', 'n@example.invalid', '+41000000061')""", it, account, handle)
                }
            }
            for (handle in listOf("player", "away", "mate")) db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, memberOf.getValue(handle))
            db.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')", team, memberOf.getValue("coach"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM teams WHERE id = ?", team)
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

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, handle: String, body: String? = null): HttpResponse =
        client.request(path) {
            this.method = method
            header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            if (method != HttpMethod.Get) { header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf) }
            if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** Day [days] from today at 18:00 Swiss time, in UTC. */
    private fun at(days: Long, hour: Int = 18): OffsetDateTime =
        LocalDate.now(zurich).plusDays(days).atTime(hour, 0).atZone(zurich).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC)

    private suspend fun ApplicationTestBuilder.event(days: Long, extra: String = ""): String {
        val body = """{"kind":"MATCH","title":"Notes match","startsAt":"${at(days)}","endsAt":"${at(days, 20)}"$extra}"""
        val response = send(HttpMethod.Post, "/api/v1/teams/$team/events", "coach", body)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json().getValue("ids").jsonArray.first().jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.answers(event: String): Map<String, JsonObject> =
        send(HttpMethod.Get, "/api/v1/events/$event/attendance", "coach").json().getValue("items").jsonArray
            .associate { it.jsonObject.getValue("displayName").jsonPrimitive.content to it.jsonObject }

    private suspend fun ApplicationTestBuilder.answer(event: String, handle: String, body: String) =
        send(HttpMethod.Put, "/api/v1/events/$event/attendance/${memberOf.getValue(handle)}", handle, body)

    @Test
    fun aNoteGoesWithTheAnswerAndOnlyCoachesAndTheAuthorSeeIt() = app {
        val event = event(3)
        assertEquals(HttpStatusCode.NoContent, answer(event, "player", """{"response":"YES","note":"  comes at 18:30 "}""").status)
        assertEquals("comes at 18:30", answers(event).getValue("player Notes").getValue("note").jsonPrimitive.content)
        val mine = send(HttpMethod.Get, "/api/v1/me/events?to=${at(10)}", "player").json().getValue("items").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == event }
        assertEquals("comes at 18:30", mine.getValue("for").jsonArray.single().jsonObject.getValue("note").jsonPrimitive.content)
        // Team members without a grant see neither answers nor notes.
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/events/$event/attendance", "mate").status)

        // Without `note` the stored note stays; null or blank removes it; too long or a control character is 400.
        assertEquals(HttpStatusCode.NoContent, answer(event, "player", """{"response":"MAYBE"}""").status)
        assertEquals("comes at 18:30", answers(event).getValue("player Notes").getValue("note").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NoContent, answer(event, "player", """{"response":"MAYBE","note":" "}""").status)
        assertEquals(JsonNull, answers(event).getValue("player Notes").getValue("note"))
        assertEquals(HttpStatusCode.BadRequest, answer(event, "player", """{"response":"YES","note":"${"x".repeat(101)}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, answer(event, "player", """{"response":"YES","note":"a\nb"}""").status)
        assertEquals(HttpStatusCode.BadRequest, answer(event, "player", """{"response":"YES","note":7}""").status)
        assertEquals(HttpStatusCode.NoContent, answer(event, "player", """{"response":"YES","note":"${"x".repeat(100)}"}""").status)
        // Clearing the answer clears its note.
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/events/$event/attendance/${memberOf.getValue("player")}", "player").status)
        assertEquals(0L, sql { (it.fetchValue("SELECT count(*) FROM event_attendance WHERE event_id = ?::uuid", event) as Number).toLong() })
    }

    @Test
    fun anAbsenceNoteStandsNextToTheImpliedNo() = app {
        val event = event(4)
        val day = LocalDate.now(zurich).plusDays(4)
        val created = send(HttpMethod.Post, "/api/v1/me/absences", "away",
            """{"memberId":"${memberOf.getValue("away")}","from":"$day","to":"$day","note":"school camp"}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val row = answers(event).getValue("away Notes")
        assertEquals("NO", row.getValue("response").jsonPrimitive.content)
        assertEquals("school camp", row.getValue("note").jsonPrimitive.content)
        val listed = send(HttpMethod.Get, "/api/v1/me/absences", "away").json().getValue("items").jsonArray.single().jsonObject
        assertEquals("school camp", listed.getValue("note").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/v1/me/absences", "away",
            """{"memberId":"${memberOf.getValue("away")}","from":"$day","to":"$day","note":"${"y".repeat(101)}"}""").status)
        // An explicit answer wins, with its own note (here none).
        assertEquals(HttpStatusCode.NoContent, answer(event, "away", """{"response":"YES"}""").status)
        assertEquals(JsonNull, answers(event).getValue("away Notes").getValue("note"))
        sql { it.execute("DELETE FROM member_absences WHERE member_id = ?", memberOf.getValue("away")) }
    }

    @Test
    fun nobodyAnswersAfterTheDeadline() = app {
        // A deadline at or after the start, or more than 60 days before it, is 400.
        for (until in listOf(at(5), at(6), at(5).minusDays(61))) {
            val body = """{"kind":"MATCH","title":"x","startsAt":"${at(5)}","endsAt":"${at(5, 20)}","answersUntil":"$until"}"""
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/v1/teams/$team/events", "coach", body).status)
        }
        val passed = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).truncatedTo(ChronoUnit.SECONDS)
        val event = event(5, ""","answersUntil":"$passed"""")
        val listed = send(HttpMethod.Get, "/api/v1/teams/$team/events?to=${at(10)}", "coach").json().getValue("items").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == event }
        assertEquals(passed.toInstant(), OffsetDateTime.parse(listed.getValue("answersUntil").jsonPrimitive.content).toInstant())

        val closed = answer(event, "player", """{"response":"YES"}""")
        assertEquals(HttpStatusCode.Conflict, closed.status)
        assertEquals("answers_closed", closed.json().getValue("code").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Delete, "/api/v1/events/$event/attendance/${memberOf.getValue("player")}", "player").status)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/v1/events/$event/attendance-reminders", "coach").status)

        // Removing the deadline reopens answers.
        val replace = """{"kind":"MATCH","title":"Notes match","startsAt":"${at(5)}","endsAt":"${at(5, 20)}","cancelled":false}"""
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$event", "coach", replace).status)
        assertEquals(HttpStatusCode.NoContent, answer(event, "player", """{"response":"YES"}""").status)
    }

    @Test
    fun weeklyCopiesKeepTheSameDeadlineBeforeTheirStart() = app {
        val until = at(6).minusDays(2)
        val body = """{"kind":"TRAINING","title":"Weekly","startsAt":"${at(6)}","endsAt":"${at(6, 20)}","answersUntil":"$until",
            "repeatWeeklyUntil":"${LocalDate.now(zurich).plusDays(13)}","timeZone":"Europe/Zurich"}"""
        val response = send(HttpMethod.Post, "/api/v1/teams/$team/events", "coach", body)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val ids = response.json().getValue("ids").jsonArray.map { it.jsonPrimitive.content }.toTypedArray()
        val leads = sql { db ->
            db.fetch("SELECT extract(epoch FROM starts_at - answers_until)::int FROM team_events WHERE id = ANY(?::uuid[])", ids)
                .map { it.get(0, Int::class.java) }
        }
        assertEquals(listOf(2 * 86400, 2 * 86400), leads)
    }

    @Test
    fun notesAreInTheMemberExport() = app {
        val event = event(8)
        val day = LocalDate.now(zurich).plusDays(9)
        assertEquals(HttpStatusCode.NoContent, answer(event, "player", """{"response":"MAYBE","note":"maybe late"}""").status)
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/v1/me/absences", "player",
            """{"memberId":"${memberOf.getValue("player")}","from":"$day","to":"$day","note":"tournament abroad"}""").status)
        val exported = sql { MemberExport(it).exportAsOperator(memberOf.getValue("player")) }!!
        val answer = exported.getValue("attendance").jsonArray.map { it.jsonObject }.single { it.getValue("eventId").jsonPrimitive.content == event }
        assertEquals("maybe late", answer.getValue("note").jsonPrimitive.content)
        assertEquals(listOf("tournament abroad"), exported.getValue("absences").jsonArray.map { it.jsonObject.getValue("note").jsonPrimitive.content })
        sql { it.execute("DELETE FROM member_absences WHERE member_id = ?", memberOf.getValue("player")) }
    }

    @Test
    fun repeatsEveryOneToFourWeeksOnlyWithARepeat() = app {
        fun body(extra: String) = """{"kind":"TRAINING","title":"Every","startsAt":"${at(6)}","endsAt":"${at(6, 20)}"$extra}"""
        val until = ""","repeatWeeklyUntil":"${LocalDate.now(zurich).plusDays(6 + 28)}","timeZone":"Europe/Zurich""""
        val created = send(HttpMethod.Post, "/api/v1/teams/$team/events", "coach", body("$until,\"repeatEveryWeeks\":2"))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals(3, created.json().getValue("ids").jsonArray.size, "weeks 0, 2 and 4")
        for (bad in listOf("$until,\"repeatEveryWeeks\":5", "$until,\"repeatEveryWeeks\":0", "$until,\"repeatEveryWeeks\":\"2\"",
                "$until,\"repeatEveryWeeks\":1.5", ",\"repeatEveryWeeks\":2")) {
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/v1/teams/$team/events", "coach", body(bad)).status, bad)
        }
    }
}
