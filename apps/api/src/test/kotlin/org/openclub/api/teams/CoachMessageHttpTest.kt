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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Coach messages (OpenAPI 1.31.0): who may send, who receives it, and the daily limit. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CoachMessageHttpTest {
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
    private val old = UUID.randomUUID()
    private val start: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withNano(0)
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
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Message Club', ?)", club, "message-$club")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Message ${team.toString().take(8)}")
            for (handle in listOf("coach", "yes", "silent", "guardian", "viewer")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "message-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Message', DATE '1980-01-01', 'm@example.invalid', '+41000000110')""",
                        it, account, handle.replaceFirstChar(Char::uppercase))
                }
            }
            db.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Message', CURRENT_DATE - 3650)", kid)
            for (member in listOf(memberOf.getValue("yes"), memberOf.getValue("silent"), kid)) {
                db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, member)
            }
            db.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'MANAGE_TEAMS')", team, memberOf.getValue("coach"))
            db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'VIEW_TEAMS')", team, memberOf.getValue("viewer"))
            for ((id, kind, at) in listOf(Triple(match, "MATCH", start), Triple(training, "TRAINING", start.plusDays(1)),
                    Triple(old, "TRAINING", start.minusDays(10)))) db.execute(
                """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at)
                   VALUES (?, ?, ?, 'Message event', ?::timestamptz, ?::timestamptz)""", id, team, kind, at.toString(), at.plusHours(2).toString())
            db.execute("INSERT INTO event_attendance (event_id, member_id, response) VALUES (?, ?, 'YES')", match, memberOf.getValue("yes"))
            db.execute("INSERT INTO event_squads (event_id) VALUES (?)", match)
            db.execute("INSERT INTO squad_members (event_id, member_id) VALUES (?, ?)", match, kid)
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

    private fun app(mail: Notifier = notifier, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database), notifier = mail) }
        block()
    }

    private suspend fun ApplicationTestBuilder.message(handle: String?, eventId: UUID, audience: String,
        subject: String = "Bring the red shirts", body: String = "Please bring the red shirts.\nThank you!"): HttpResponse =
        client.request("/api/v1/events/$eventId/messages") {
            method = HttpMethod.Post
            if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"audience":"$audience","subject":${kotlinx.serialization.json.JsonPrimitive(subject)},"body":${kotlinx.serialization.json.JsonPrimitive(body)}}""")
        }

    private fun mails(handle: String): List<String> = sql {
        it.fetch("SELECT body FROM email_outbox WHERE kind = 'TEAM_MESSAGE' AND member_id = ? ORDER BY created_at", memberOf.getValue(handle))
            .map { row -> row.get(0, String::class.java) }
    }

    private suspend fun HttpResponse.members(): Int {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("members").jsonPrimitive.int
    }

    @Test
    @Order(1)
    fun eachAudienceReachesItsPeopleAndGuardiansForChildren() = app {
        assertEquals(3, message("coach", match, "ALL").members())
        assertEquals(1, mails("yes").size)
        assertEquals(1, mails("silent").size)
        val guardianMail = mails("guardian").single()
        assertTrue("Kiddo" in guardianMail && "Coach Message" in guardianMail && "red shirts" in guardianMail, guardianMail)
        assertEquals(1, message("coach", match, "YES").members())
        assertEquals(2, mails("yes").size)
        assertEquals(1, mails("silent").size, "YES leaves out members who have not said yes")
        assertEquals(2, message("coach", match, "NO_ANSWER").members())
        assertEquals(2, mails("silent").size)
        assertEquals(1, message("coach", match, "SQUAD").members())
        assertEquals(3, mails("guardian").size, "the squad is the child, reached through the guardian")
        assertEquals(2, mails("silent").size)
        assertEquals(0, mails("coach").size, "the coach is not in the team")
    }

    @Test
    @Order(2)
    fun onlyTheTeamsManagersSendAndTheInputIsChecked() = app {
        val failures = mutableListOf<String>()
        suspend fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status} ${response.bodyAsText()}"
        }
        expect("signed out", message(null, match, "ALL"), HttpStatusCode.Unauthorized)
        expect("viewer", message("viewer", match, "ALL"), HttpStatusCode.Forbidden)
        expect("member", message("yes", match, "ALL"), HttpStatusCode.Forbidden)
        expect("unknown event", message("coach", UUID.randomUUID(), "ALL"), HttpStatusCode.Forbidden)
        expect("squad of a training", message("coach", training, "SQUAD"), HttpStatusCode.Conflict)
        expect("long past", message("coach", old, "ALL"), HttpStatusCode.Conflict)
        expect("unknown audience", message("coach", match, "PARENTS"), HttpStatusCode.BadRequest)
        expect("empty subject", message("coach", match, "ALL", subject = " "), HttpStatusCode.BadRequest)
        expect("line break in subject", message("coach", match, "ALL", subject = "a\nBcc: x@example.invalid"), HttpStatusCode.BadRequest)
        expect("long body", message("coach", match, "ALL", body = "x".repeat(5001)), HttpStatusCode.BadRequest)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    @Order(3)
    fun theSwitchTheLimitAndNoMailConfiguration() = app {
        sql { it.execute("INSERT INTO notification_settings (member_id, team_messages) VALUES (?, FALSE)", memberOf.getValue("yes")) }
        val before = mails("yes").size
        assertEquals(1, message("coach", match, "YES").members(), "the member still counts")
        assertEquals(before, mails("yes").size, "but with the switch off gets no email")
        // Five sent so far; five more reach the limit of ten per team and day.
        repeat(5) { assertEquals(HttpStatusCode.OK, message("coach", training, "ALL").status) }
        val limited = message("coach", training, "ALL")
        assertEquals(HttpStatusCode.Conflict, limited.status)
        assertTrue("limit" in limited.bodyAsText())
        assertEquals(10, sql { (it.fetchValue("SELECT count(*) FROM coach_messages WHERE team_id = ?", team) as Number).toInt() })
        assertTrue(sql { it.fetch("SELECT * FROM coach_messages WHERE team_id = ?", team).fields().none { field -> field.name in setOf("subject", "body") } },
            "the log keeps no text")
    }

    @Test
    @Order(4)
    fun withoutEmailNothingIsSent() = app(Notifier.DISABLED) {
        val response = message("coach", training, "ALL")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue("email_off" in response.bodyAsText())
    }
}
