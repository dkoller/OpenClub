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
import kotlinx.serialization.json.boolean
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Actual attendance and the J+S export (OpenAPI 1.27.0): who may record, what is recorded, and the NDS file. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EventPresenceHttpTest {
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
    private val training = UUID.randomUUID()
    private val match = UUID.randomUUID()
    private val future = UUID.randomUUID()
    private val foreign = UUID.randomUUID()
    private val swiss = ZoneId.of("Europe/Zurich")
    private val trainingStart: ZonedDateTime = LocalDate.now(swiss).minusDays(2).atTime(18, 30).atZone(swiss)
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
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Presence Club', ?)", club, "presence-$club")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Presence ${team.toString().take(8)}")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", otherTeam, "Elsewhere ${otherTeam.toString().take(8)}")
            // coach manages the team; viewer only views it; player plays; editor edits members; other coaches elsewhere.
            for (handle in listOf("coach", "viewer", "player", "editor", "other")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "presence-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Presence', DATE '1980-01-01', 'p@example.invalid', '+41000000070')""",
                        it, account, handle.replaceFirstChar(Char::uppercase))
                }
            }
            db.execute("UPDATE members SET js_person_number = '123456789' WHERE id = ?", memberOf.getValue("player"))
            db.execute("UPDATE members SET js_person_number = '987654321' WHERE id = ?", memberOf.getValue("coach"))
            db.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Presence', CURRENT_DATE - 3650)", kid)
            for (member in listOf(memberOf.getValue("player"), kid)) db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, member)
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'MANAGE_TEAMS')", team, memberOf.getValue("coach"))
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'VIEW_TEAMS')", team, memberOf.getValue("viewer"))
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'MANAGE_TEAMS')", otherTeam, memberOf.getValue("other"))
            db.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", memberOf.getValue("editor"))
            fun event(id: UUID, teamId: UUID, kind: String, starts: ZonedDateTime, location: String?) = db.execute(
                """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, location)
                   VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?)""",
                id, teamId, kind, "$kind event", starts.toOffsetDateTime().toString(), starts.plusMinutes(90).toOffsetDateTime().toString(), location)
            event(training, team, "TRAINING", trainingStart, "Halle Süd; =Feld 2")
            event(match, team, "MATCH", trainingStart.plusDays(1), null)
            event(future, team, "TRAINING", trainingStart.plusDays(9), null)
            event(foreign, otherTeam, "TRAINING", trainingStart, null)
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM audit_events WHERE actor_account_id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM teams WHERE id = ANY(?::uuid[])", arrayOf(team, otherTeam))
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

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod, path: String, handle: String?, body: String? = null, headers: Map<String, String> = emptyMap(),
    ): HttpResponse = client.request(path) {
        this.method = method
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        if (method != HttpMethod.Get) { header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf) }
        headers.forEach { (name, value) -> header(name, value) }
        if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun ids(vararg members: UUID) = """{"present":[${members.joinToString(",") { "\"$it\"" }}]}"""

    @Test
    @Order(1)
    fun theCoachRecordsWhoCameOnceTheEventStarted() = app {
        val read = send(HttpMethod.Get, "/api/v1/events/$training/presence", "coach")
        assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
        val body = read.json()
        assertTrue(body.getValue("open").jsonPrimitive.boolean)
        val people = body.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Coach Presence" to "LEADER", "Kiddo P." to "PARTICIPANT", "Player Presence" to "PARTICIPANT"),
            people.map { it.getValue("displayName").jsonPrimitive.content to it.getValue("role").jsonPrimitive.content })
        assertEquals(setOf("memberId", "displayName", "role", "response", "present"), people.first().keys)

        val coach = memberOf.getValue("coach")
        val player = memberOf.getValue("player")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$training/presence", "coach", ids(coach, player, kid)).status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$match/presence", "coach", ids(player)).status)
        val present = send(HttpMethod.Get, "/api/v1/events/$training/presence", "coach").json().getValue("items").jsonArray
            .count { it.jsonObject.getValue("present").jsonPrimitive.boolean }
        assertEquals(3, present)
    }

    @Test
    @Order(2)
    fun onlyTheTeamsManagersMayReadOrRecordAndOnlyTheEventsPeople() = app {
        val failures = mutableListOf<String>()
        suspend fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status} ${response.bodyAsText()}"
        }
        val path = "/api/v1/events/$training/presence"
        val player = memberOf.getValue("player")
        expect("signed out", send(HttpMethod.Get, path, null), HttpStatusCode.Unauthorized)
        expect("viewer reads", send(HttpMethod.Get, path, "viewer"), HttpStatusCode.Forbidden)
        expect("player reads", send(HttpMethod.Get, path, "player"), HttpStatusCode.Forbidden)
        expect("other coach reads", send(HttpMethod.Get, path, "other"), HttpStatusCode.Forbidden)
        expect("other coach writes", send(HttpMethod.Put, path, "other", ids(player)), HttpStatusCode.Forbidden)
        expect("unknown event", send(HttpMethod.Get, "/api/v1/events/${UUID.randomUUID()}/presence", "coach"), HttpStatusCode.NotFound)
        expect("not started", send(HttpMethod.Put, "/api/v1/events/$future/presence", "coach", ids(player)), HttpStatusCode.Conflict)
        expect("someone else", send(HttpMethod.Put, path, "coach", ids(memberOf.getValue("editor"))), HttpStatusCode.BadRequest)
        expect("other team's player", send(HttpMethod.Put, "/api/v1/events/$foreign/presence", "coach", ids(player)), HttpStatusCode.Forbidden)
        expect("duplicates", send(HttpMethod.Put, path, "coach", ids(player, player)), HttpStatusCode.BadRequest)
        expect("not an array", send(HttpMethod.Put, path, "coach", """{"present":"$player"}"""), HttpStatusCode.BadRequest)
        expect("extra field", send(HttpMethod.Put, path, "coach", """{"present":[],"note":"x"}"""), HttpStatusCode.BadRequest)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    @Order(3)
    fun theJsFileListsNumberedPeopleAndTheCheckNamesTheRest() = app {
        val from = trainingStart.toLocalDate().minusDays(1)
        val to = trainingStart.toLocalDate().plusDays(1)
        val check = send(HttpMethod.Get, "/api/v1/teams/$team/js-attendance?from=$from&to=$to", "coach")
        assertEquals(HttpStatusCode.OK, check.status, check.bodyAsText())
        val body = check.json()
        assertEquals(3, body.getValue("rows").jsonPrimitive.int)
        assertEquals(listOf("Kiddo P."), body.getValue("withoutNumber").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, body.getValue("trainingsWithoutPlace").jsonArray.size)

        val audits = { sql { it.fetchValue("SELECT count(*) FROM audit_events WHERE event_type = 'JS_ATTENDANCE_EXPORTED' AND actor_account_id = ?", accounts.getValue("coach")) as Number }.toInt() }
        assertEquals(0, audits(), "the check is not audited")
        val file = send(HttpMethod.Get, "/api/v1/teams/$team/js-attendance.csv?from=$from&to=$to", "coach")
        assertEquals(HttpStatusCode.OK, file.status)
        val date = trainingStart.toLocalDate()
        val day = { d: LocalDate -> "%02d.%02d.%04d".format(d.dayOfMonth, d.monthValue, d.year) }
        assertEquals(
            "PERSONENNUMMER;FUNKTION;DATUM;AKTIVITAETSTYP;ZEIT;DAUER;ORT\r\n" +
                "987654321;Leiter/in;${day(date)};Training;18:30;90;Halle Süd  =Feld 2\r\n" +
                "123456789;Teilnehmer/in;${day(date)};Training;18:30;90;Halle Süd  =Feld 2\r\n" +
                "123456789;Teilnehmer/in;${day(date.plusDays(1))};Wettkampf;;;\r\n",
            file.bodyAsText())
        assertEquals(1, audits())
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/teams/$team/js-attendance.csv", "coach",
            headers = mapOf("Sec-Fetch-Site" to "cross-site")).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/teams/$team/js-attendance", "viewer").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/teams/$team/js-attendance", "other").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, "/api/v1/teams/$team/js-attendance?from=$to&to=$from", "coach").status)
    }

    @Test
    @Order(4)
    fun aRecordOutlivesTheTeamAssignment() = app {
        sql { it.execute("DELETE FROM member_team_assignments WHERE team_id = ? AND member_id = ?", team, memberOf.getValue("player")) }
        val people = send(HttpMethod.Get, "/api/v1/events/$training/presence", "coach").json().getValue("items").jsonArray
        assertTrue(people.any { it.jsonObject.getValue("displayName").jsonPrimitive.content == "Player Presence" &&
            it.jsonObject.getValue("present").jsonPrimitive.boolean }, "the record stays after leaving the team")
        // Saving again keeps the former member, who is still one of the event's people through the record.
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$training/presence", "coach",
            ids(memberOf.getValue("player"))).status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$training/presence", "coach", ids()).status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, "/api/v1/events/$training/presence", "coach",
            ids(memberOf.getValue("player"))).status, "once cleared, a former member cannot be added again")
    }

    @Test
    @Order(5)
    fun editorsSetTheJsNumberWithTheMembersVersion() = app {
        val member = memberOf.getValue("viewer")
        val path = "/api/v1/members/$member/js-person-number"
        val version = sql { it.fetchValue("SELECT row_version FROM members WHERE id = ?", member) as Number }.toLong()
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, path, "coach", """{"jsPersonNumber":"1"}""",
            mapOf(HttpHeaders.IfMatch to "\"$version\"")).status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, path, "editor", """{"jsPersonNumber":"12a"}""",
            mapOf(HttpHeaders.IfMatch to "\"$version\"")).status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, path, "editor", """{"jsPersonNumber":12}""",
            mapOf(HttpHeaders.IfMatch to "\"$version\"")).status)
        assertEquals(HttpStatusCode.PreconditionRequired, send(HttpMethod.Put, path, "editor", """{"jsPersonNumber":"1"}""").status)
        val set = send(HttpMethod.Put, path, "editor", """{"jsPersonNumber":"555000111"}""", mapOf(HttpHeaders.IfMatch to "\"$version\""))
        assertEquals(HttpStatusCode.NoContent, set.status, set.bodyAsText())
        assertEquals(HttpStatusCode.PreconditionFailed, send(HttpMethod.Put, path, "editor", """{"jsPersonNumber":null}""",
            mapOf(HttpHeaders.IfMatch to "\"$version\"")).status)
        assertEquals("555000111", send(HttpMethod.Get, "/api/v1/me/profile", "viewer").json().getValue("jsPersonNumber").jsonPrimitive.content)
        assertEquals(1, sql { it.fetchValue("SELECT count(*) FROM audit_events WHERE event_type = 'JS_NUMBER_CHANGED' AND target_member_id = ?", member) as Number }.toInt())
    }
}
