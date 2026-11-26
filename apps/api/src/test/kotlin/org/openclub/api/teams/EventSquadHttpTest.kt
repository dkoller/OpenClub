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
import org.openclub.api.notifications.MailConfig
import org.openclub.api.notifications.Notifier
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Match squads (OpenAPI 1.28.0): who may call up, who is emailed, and what members see of it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EventSquadHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val team = UUID.randomUUID()
    private val kid = UUID.randomUUID()
    private val match = UUID.randomUUID()
    private val training = UUID.randomUUID()
    private val pastMatch = UUID.randomUUID()
    private val start: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5).withNano(0)
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
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Squad Club', ?)", club, "squad-$club")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Squad ${team.toString().take(8)}")
            for (handle in listOf("coach", "viewer", "player", "guardian", "outsider")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "squad-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Squad', DATE '1980-01-01', 's@example.invalid', '+41000000080')""",
                        it, account, handle.replaceFirstChar(Char::uppercase))
                }
            }
            db.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Squad', CURRENT_DATE - 3650)", kid)
            for (member in listOf(memberOf.getValue("player"), kid)) db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, member)
            db.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'MANAGE_TEAMS')", team, memberOf.getValue("coach"))
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'VIEW_TEAMS')", team, memberOf.getValue("viewer"))
            for ((id, kind, at) in listOf(Triple(match, "MATCH", start), Triple(training, "TRAINING", start.plusDays(1)),
                    Triple(pastMatch, "MATCH", start.minusDays(10)))) db.execute(
                """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at)
                   VALUES (?, ?, ?, 'Squad event', ?::timestamptz, ?::timestamptz)""", id, team, kind, at.toString(), at.plusHours(2).toString())
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM email_outbox WHERE member_id = ANY(?::uuid[])", (memberOf.values + kid).toTypedArray())
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
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database), notifier = notifier) }
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
    private fun squad(vararg members: UUID, meetAt: String? = null, point: String? = null) =
        """{"memberIds":[${members.joinToString(",") { "\"$it\"" }}],"meetAt":${meetAt?.let { "\"$it\"" } ?: "null"},"meetingPoint":${point?.let { "\"$it\"" } ?: "null"}}"""
    private fun calls(member: UUID): Int = sql {
        (it.fetchValue("SELECT count(*) FROM email_outbox WHERE kind = 'SQUAD_CALLED' AND member_id = ?", member) as Number).toInt()
    }

    @Test
    @Order(1)
    fun theCoachCallsUpAndTheCalledAreEmailedOnce() = app {
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Get, "/api/v1/events/$training/squad", "coach").status)
        val empty = send(HttpMethod.Get, "/api/v1/events/$match/squad", "coach").json()
        assertEquals(listOf("Kiddo S." to false, "Player Squad" to false), empty.getValue("items").jsonArray.map {
            it.jsonObject.getValue("displayName").jsonPrimitive.content to it.jsonObject.getValue("called").jsonPrimitive.boolean
        })
        val meet = start.minusHours(1).toString()
        val player = memberOf.getValue("player")
        val saved = send(HttpMethod.Put, "/api/v1/events/$match/squad", "coach", squad(player, kid, meetAt = meet, point = "Parkplatz Nord"))
        assertEquals(HttpStatusCode.NoContent, saved.status, saved.bodyAsText())
        assertEquals(1, calls(player))
        assertEquals(1, calls(memberOf.getValue("guardian")), "the guardian is emailed for the called child")
        val body = sql { it.fetchValue("SELECT body FROM email_outbox WHERE kind = 'SQUAD_CALLED' AND member_id = ?", memberOf.getValue("guardian")) as String }
        assertTrue(body.contains("Kiddo") && body.contains("Parkplatz Nord"), body)

        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$match/squad", "coach", squad(player, kid, meetAt = meet)).status)
        assertEquals(1, calls(player), "saving again emails nobody who was already called")
        val read = send(HttpMethod.Get, "/api/v1/events/$match/squad", "coach").json()
        assertEquals(start.minusHours(1).toString(), read.getValue("meetAt").jsonPrimitive.content)
        assertEquals(JsonNull, read.getValue("meetingPoint"))
    }

    @Test
    @Order(2)
    fun membersAndGuardiansSeeOnlyTheirOwnStatus() = app {
        val range = "?from=${start.minusDays(1)}&to=${start.plusDays(3)}".replace("+", "%2B")
        fun JsonObject.forItems(eventId: UUID) = getValue("items").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == eventId.toString() }.getValue("for").jsonArray.map { it.jsonObject }
        val mine = send(HttpMethod.Get, "/api/v1/me/events$range", "player").json()
        val own = mine.forItems(match).single().getValue("squad").jsonObject
        assertTrue(own.getValue("called").jsonPrimitive.boolean)
        assertEquals(start.minusHours(1).toString(), own.getValue("meetAt").jsonPrimitive.content)
        assertEquals(JsonNull, mine.forItems(training).single().getValue("squad"), "a training has no squad")
        val child = send(HttpMethod.Get, "/api/v1/me/events$range", "guardian").json().forItems(match).single()
        assertEquals("Kiddo", child.getValue("firstName").jsonPrimitive.content)
        assertTrue(child.getValue("squad").jsonObject.getValue("called").jsonPrimitive.boolean)

        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$match/squad", "coach",
            squad(memberOf.getValue("player"))).status)
        val dropped = send(HttpMethod.Get, "/api/v1/me/events$range", "guardian").json().forItems(match).single().getValue("squad").jsonObject
        assertEquals(false, dropped.getValue("called").jsonPrimitive.boolean)
        assertEquals(JsonNull, dropped.getValue("meetAt"), "meeting details only for the called")
    }

    @Test
    @Order(3)
    fun onlyTheTeamsManagersCallUpTeamMembersBeforeTheMatch() = app {
        val failures = mutableListOf<String>()
        suspend fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status} ${response.bodyAsText()}"
        }
        val path = "/api/v1/events/$match/squad"
        val player = memberOf.getValue("player")
        expect("signed out", send(HttpMethod.Get, path, null), HttpStatusCode.Unauthorized)
        expect("viewer reads", send(HttpMethod.Get, path, "viewer"), HttpStatusCode.Forbidden)
        expect("player writes", send(HttpMethod.Put, path, "player", squad(player)), HttpStatusCode.Forbidden)
        // A team-scoped coach cannot tell an unknown event from another team's; only a club-wide manager gets 404.
        expect("unknown event, team coach", send(HttpMethod.Get, "/api/v1/events/${UUID.randomUUID()}/squad", "coach"), HttpStatusCode.Forbidden)
        sql { it.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_TEAMS')", memberOf.getValue("viewer")) }
        expect("unknown event, club-wide", send(HttpMethod.Get, "/api/v1/events/${UUID.randomUUID()}/squad", "viewer"), HttpStatusCode.NotFound)
        sql { it.execute("DELETE FROM club_capability_grants WHERE member_id = ? AND capability = 'MANAGE_TEAMS'", memberOf.getValue("viewer")) }
        expect("training", send(HttpMethod.Put, "/api/v1/events/$training/squad", "coach", squad(player)), HttpStatusCode.Conflict)
        expect("past match", send(HttpMethod.Put, "/api/v1/events/$pastMatch/squad", "coach", squad(player)), HttpStatusCode.Conflict)
        expect("not in the team", send(HttpMethod.Put, path, "coach", squad(memberOf.getValue("outsider"))), HttpStatusCode.BadRequest)
        expect("bad time", send(HttpMethod.Put, path, "coach", """{"memberIds":[],"meetAt":"tomorrow","meetingPoint":null}"""), HttpStatusCode.BadRequest)
        expect("missing field", send(HttpMethod.Put, path, "coach", """{"memberIds":[]}"""), HttpStatusCode.BadRequest)
        expect("long point", send(HttpMethod.Put, path, "coach", squad(point = "x".repeat(201))), HttpStatusCode.BadRequest)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    @Order(4)
    fun theSwitchStopsTheEmailAndLeavingTheTeamLeavesTheSquad() = app {
        val player = memberOf.getValue("player")
        val settings = send(HttpMethod.Get, "/api/v1/me/notifications", "player").json()
        assertTrue(settings.getValue("squadCalls").jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/me/notifications", "player",
            """{"eventChanges":true,"newEvents":true,"announcements":true,"shiftReminders":true,"language":"de","squadCalls":false}""").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$match/squad", "coach", squad()).status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$match/squad", "coach", squad(player)).status)
        assertEquals(1, calls(player), "switched off: no second email")
        sql { it.execute("DELETE FROM member_team_assignments WHERE team_id = ? AND member_id = ?", team, player) }
        assertEquals(0, sql { (it.fetchValue("SELECT count(*) FROM squad_members WHERE event_id = ?", match) as Number).toInt() })
    }
}
