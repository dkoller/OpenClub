package org.openclub.api.join

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.module
import java.time.LocalDate
import java.util.UUID

/**
 * Join requests (OpenAPI 1.22.0): the switch, the public form with its abuse limits, and accepting or declining.
 * Every test starts with the form closed and no requests from this class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JoinRequestHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val team = UUID.randomUUID()
    private val created = mutableListOf<UUID>()
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }
    private fun count(query: String, vararg args: Any): Int = sql { (it.fetchValue(query, *args) as Number).toInt() }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { db ->
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Join Club', ?)", club, "join-$club")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Join ${team.toString().take(8)}")
            for (handle in listOf("editor", "plain")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "join-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Join', DATE '1980-01-01', 'j@example.invalid', '+41000000080')""", it, account, handle)
                }
            }
            db.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'EDIT_MEMBERS')", memberOf.getValue("editor"))
        }
    }

    @BeforeEach
    fun reset() {
        if (database == null) return
        sql { db ->
            db.execute("DELETE FROM join_requests WHERE email LIKE '%@join.example.invalid'")
            db.execute("UPDATE clubs SET join_requests_open = FALSE")
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM join_requests WHERE email LIKE '%@join.example.invalid'")
                tx.execute("DELETE FROM teams WHERE id = ?", team)
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", (memberOf.values + created).toTypedArray())
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
        withCsrf: String? = csrf, withOrigin: String? = origin, forwardedFor: String? = null): HttpResponse = client.request(path) {
        this.method = method
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        if (method != HttpMethod.Get) { if (withOrigin != null) header(HttpHeaders.Origin, withOrigin); if (withCsrf != null) header("X-CSRF-Token", withCsrf) }
        if (forwardedFor != null) header(HttpHeaders.XForwardedFor, forwardedFor)
        if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun adult(name: String = "Ada", website: String = "", accepted: Boolean = true, extra: String = "") =
        """{"kind":"SELF","firstName":"$name","lastName":"Applicant","dateOfBirth":"1990-05-01","email":"${name.lowercase()}@join.example.invalid",
            "phone":"+41 79 000 00 00","teamId":"$team","message":"I play goalkeeper","acceptedPrivacyNotice":$accepted,"website":"$website"$extra}"""

    private val childBirth = LocalDate.now().minusYears(9)
    private fun child() = """{"kind":"CHILD","firstName":"Kiddo","lastName":"Applicant","dateOfBirth":"$childBirth",
        "email":"parent@join.example.invalid","phone":"+41 79 000 00 01","parentFirstName":"Pat","parentLastName":"Applicant",
        "acceptedPrivacyNotice":true,"website":""}"""

    private suspend fun ApplicationTestBuilder.open() =
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/club/join-requests/settings", "editor", """{"open":true}""").status)

    @Test
    fun theFormIsClosedUntilAnEditorOpensIt() = app {
        assertFalse(send(HttpMethod.Get, "/api/v1/public/join-requests", null).json().getValue("open").jsonPrimitive.boolean)
        assertTrue(send(HttpMethod.Get, "/api/v1/public/join-requests", null).json().getValue("teams").jsonArray.isEmpty(), "no teams while closed")
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/v1/public/join-requests", null, adult()).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, "/api/v1/club/join-requests/settings", "plain", """{"open":true}""").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/club/join-requests/settings", "plain").status)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/v1/club/join-requests/settings", null).status)
        assertEquals(HttpStatusCode.Forbidden,
            send(HttpMethod.Put, "/api/v1/club/join-requests/settings", "editor", """{"open":true}""", withCsrf = null).status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, "/api/v1/club/join-requests/settings", "editor", """{"open":"yes"}""").status)
        open()
        assertTrue(send(HttpMethod.Get, "/api/v1/club/join-requests/settings", "editor").json().getValue("open").jsonPrimitive.boolean)
        val form = send(HttpMethod.Get, "/api/v1/public/join-requests", null).json()
        assertTrue(form.getValue("open").jsonPrimitive.boolean)
        assertTrue(form.getValue("teams").jsonArray.any { it.jsonObject.getValue("id").jsonPrimitive.content == team.toString() })
    }

    @Test
    fun thePublicFormValidatesAndLimitsWithoutRevealingAnything() = app {
        open()
        val path = "/api/v1/public/join-requests"
        val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
        }
        expect("no Origin", send(HttpMethod.Post, path, null, adult(), withOrigin = null, forwardedFor = "198.51.100.1"), HttpStatusCode.Forbidden)
        expect("other Origin", send(HttpMethod.Post, path, null, adult(), withOrigin = "https://evil.example", forwardedFor = "198.51.100.1"), HttpStatusCode.Forbidden)
        for ((label, body) in listOf("privacy notice not accepted" to adult(accepted = false),
            "child too old" to child().replace("$childBirth", "1990-01-01"),
            "adult too young" to adult().replace("1990-05-01", "$childBirth"),
            "parent missing" to child().replace(""","parentFirstName":"Pat","parentLastName":"Applicant"""", ""),
            "parent for an adult" to adult(extra = ""","parentFirstName":"Pat","parentLastName":"X""""),
            "unknown key" to adult(extra = ""","ip":"1.2.3.4""""), "bad email" to adult().replace("ada@join", "ada join"),
            "unknown team" to adult().replace("$team", "${UUID.randomUUID()}"))) {
            expect(label, send(HttpMethod.Post, path, null, body, forwardedFor = "198.51.100.${label.length}"), HttpStatusCode.BadRequest)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
        assertEquals(0, count("SELECT count(*) FROM join_requests WHERE email LIKE '%@join.example.invalid'"), "nothing invalid is stored")

        assertEquals(HttpStatusCode.Accepted, send(HttpMethod.Post, path, null, adult(website = "http://spam"), forwardedFor = "203.0.113.9").status)
        assertEquals(0, count("SELECT count(*) FROM join_requests WHERE email LIKE '%@join.example.invalid'"), "the honeypot drops it silently")
        assertEquals(HttpStatusCode.Accepted, send(HttpMethod.Post, path, null, adult(), forwardedFor = "203.0.113.9").status)
        assertEquals(HttpStatusCode.Accepted, send(HttpMethod.Post, path, null, adult(), forwardedFor = "203.0.113.9").status, "a repeat reveals nothing")
        assertEquals(1, count("SELECT count(*) FROM join_requests WHERE email = 'ada@join.example.invalid'"), "but is stored once")
        repeat(2) { send(HttpMethod.Post, path, null, adult(), forwardedFor = "203.0.113.9") } // five from this address now
        assertEquals(HttpStatusCode.TooManyRequests, send(HttpMethod.Post, path, null, adult("Bea"), forwardedFor = "203.0.113.9").status)
        assertEquals(HttpStatusCode.Accepted, send(HttpMethod.Post, path, null, adult("Bea"), forwardedFor = "10.0.0.1, 203.0.113.10").status,
            "the limit is per client address (the last forwarded entry)")
        assertEquals(0, count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'join_requests' AND column_name ILIKE '%ip%'"),
            "no address is stored")
    }

    @Test
    fun editorsAcceptOrDeclineRequests() = app {
        open()
        assertEquals(HttpStatusCode.Accepted, send(HttpMethod.Post, "/api/v1/public/join-requests", null, adult("Cleo")).status)
        assertEquals(HttpStatusCode.Accepted, send(HttpMethod.Post, "/api/v1/public/join-requests", null, child()).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/join-requests", "plain").status)
        val items = send(HttpMethod.Get, "/api/v1/join-requests", "editor").json().getValue("items").jsonArray.map { it.jsonObject }
            .filter { it.getValue("email").jsonPrimitive.content.endsWith("@join.example.invalid") }
        assertEquals(2, items.size)
        val adultRequest = items.single { it.getValue("kind").jsonPrimitive.content == "SELF" }
        val childRequest = items.single { it.getValue("kind").jsonPrimitive.content == "CHILD" }
        assertEquals(setOf("id", "createdAt", "kind", "firstName", "lastName", "dateOfBirth", "email", "phone", "parentFirstName",
            "parentLastName", "teamName", "message"), adultRequest.keys)
        assertEquals("Pat", childRequest.getValue("parentFirstName").jsonPrimitive.content)

        val adultId = adultRequest.getValue("id").jsonPrimitive.content
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/v1/join-requests/$adultId/accept", "plain").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/v1/join-requests/$adultId/accept", "editor", withCsrf = null).status)
        val accepted = send(HttpMethod.Post, "/api/v1/join-requests/$adultId/accept", "editor")
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
        val member = UUID.fromString(accepted.json().getValue("memberId").jsonPrimitive.content).also { created += it }
        assertEquals("cleo@join.example.invalid", sql { it.fetchValue("SELECT email FROM members WHERE id = ?", member) })
        assertEquals(1, count("SELECT count(*) FROM member_team_assignments WHERE team_id = ? AND member_id = ?", team, member), "the wished team")
        assertEquals(1, count("SELECT count(*) FROM audit_events WHERE event_type = 'MEMBER_CREATED' AND target_member_id = ?", member))
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "/api/v1/join-requests/$adultId/accept", "editor").status, "the request is gone")

        val childId = childRequest.getValue("id").jsonPrimitive.content
        val kid = UUID.fromString(send(HttpMethod.Post, "/api/v1/join-requests/$childId/accept", "editor").json()
            .getValue("memberId").jsonPrimitive.content).also { created += it }
        assertNull(sql { it.fetchValue("SELECT email FROM members WHERE id = ?", kid) }, "a child gets no contacts; the parent's stay out")

        assertEquals(HttpStatusCode.Accepted, send(HttpMethod.Post, "/api/v1/public/join-requests", null, adult("Dora")).status)
        val dora = sql { it.fetchValue("SELECT id FROM join_requests WHERE email = 'dora@join.example.invalid'").toString() }
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Delete, "/api/v1/join-requests/$dora", "plain").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/join-requests/$dora", "editor").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Delete, "/api/v1/join-requests/$dora", "editor").status)
    }

    @Test
    fun theLimiterForgetsAfterTheWindow() {
        val limiter = JoinRequestLimiter(limit = 2, windowMillis = 1000)
        assertTrue(limiter.allow("a", 0)); assertTrue(limiter.allow("a", 10)); assertFalse(limiter.allow("a", 20))
        assertTrue(limiter.allow("b", 20), "per address")
        assertTrue(limiter.allow("a", 1011), "after the window")
    }
}
