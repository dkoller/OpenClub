package org.openclub.api.members

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
import org.openclub.api.auth.AuthAdmissionLimiter
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.module
import java.util.UUID
import org.openclub.api.checkContract

/** Invitation and unlink routes (OpenAPI 1.2.0): order of answers, generic refusals, rate limit, end to end. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberInvitationHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val members = mutableListOf<UUID>()
    private val handles = listOf("editor", "manager", "plain", "invitee", "stranger", "second")
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
        sql { tx ->
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Invitation HTTP Club', ?)", club, "invite-http-$club")
            for (handle in handles) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                // "stranger" has no verified identity at all.
                if (handle != "stranger") tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "http-$account")
            }
            fun linked(handle: String, role: String) = UUID.randomUUID().also {
                members += it
                tx.execute(
                    """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                       VALUES (?, ?, 'Http', 'Actor', DATE '1985-01-01', 'a@example.invalid', '+41000000000', ?)""",
                    it, accounts.getValue(handle), role)
            }
            val editor = linked("editor", "ADMIN")
            val manager = linked("manager", "ADMIN")
            linked("plain", "MEMBER")
            tx.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", editor)
            tx.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", manager)
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", members.toTypedArray())
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", members.toTypedArray())
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun adultMember(): UUID = UUID.randomUUID().also { id ->
        members += id
        sql { it.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone)
            VALUES (?, 'Invited', 'Person', DATE '1990-01-01', 'p@example.invalid', '+41000000002')""", id) }
    }

    private fun app(limiter: AuthAdmissionLimiter = AuthAdmissionLimiter(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { checkContract();
            module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database), authAdmission = limiter)
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod, path: String, handle: String?, body: String? = null,
        contentType: String? = "application/json", withOrigin: String? = origin, withCsrf: String? = csrf,
    ): HttpResponse = client.request(path) {
        this.method = method
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        if (withOrigin != null) header(HttpHeaders.Origin, withOrigin)
        if (withCsrf != null) header("X-CSRF-Token", withCsrf)
        if (body != null) { if (contentType != null) header(HttpHeaders.ContentType, contentType); setBody(body) }
    }

    private fun invitation(id: UUID) = "/api/v1/members/$id/invitation"
    private fun unlink(id: UUID) = "/api/v1/members/$id/account-link"
    private val accept = "/api/v1/invitations/accept"

    private class Check { val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode, body: String) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
        } }

    @Test
    fun theOrderOfAnswersIsSessionThenOriginAndCsrfThenCapabilityThenValidation() = app {
        val member = adultMember()
        val routes = listOf(
            Triple(HttpMethod.Post, invitation(member), "editor"), Triple(HttpMethod.Delete, invitation(member), "editor"),
            Triple(HttpMethod.Delete, unlink(member), "manager"), Triple(HttpMethod.Post, accept, "invitee"))
        val check = Check()
        for ((method, path, right) in routes) {
            val body = if (path == accept) """{"secret":"${"A".repeat(43)}"}""" else null
            val label = "$method $path"
            check.expect("$label no session", send(method, path, null, body), HttpStatusCode.Unauthorized, "")
            check.expect("$label unknown session", send(method, path, "ghost", body), HttpStatusCode.Unauthorized, "")
            check.expect("$label no Origin", send(method, path, right, body, withOrigin = null), HttpStatusCode.Forbidden, "")
            check.expect("$label wrong Origin", send(method, path, right, body, withOrigin = "https://evil.example"), HttpStatusCode.Forbidden, "")
            check.expect("$label no CSRF", send(method, path, right, body, withCsrf = null), HttpStatusCode.Forbidden, "")
            check.expect("$label wrong CSRF", send(method, path, right, body, withCsrf = "y".repeat(43)), HttpStatusCode.Forbidden, "")
        }
        // Without the capability the caller gets 403 whatever they send, never 400: nothing about the expected input leaks.
        val forbidden = listOf(Triple(HttpMethod.Post, "plain", "create"), Triple(HttpMethod.Delete, "plain", "revoke"),
            Triple(HttpMethod.Post, "manager", "create without EDIT_MEMBERS"), Triple(HttpMethod.Delete, "manager", "revoke without EDIT_MEMBERS"))
        for ((method, handle, label) in forbidden) {
            for (path in listOf(invitation(member), "/api/v1/members/not-a-uuid/invitation", "/api/v1/members/${UUID.randomUUID()}/invitation")) {
                check.expect("$label $path", send(method, path, handle, "{garbage"), HttpStatusCode.Forbidden, "")
            }
        }
        for (handle in listOf("plain", "editor")) {
            for (path in listOf(unlink(member), "/api/v1/members/not-a-uuid/account-link")) {
                check.expect("unlink by $handle $path", send(HttpMethod.Delete, path, handle, "{garbage"), HttpStatusCode.Forbidden, "")
            }
        }
        assertTrue(check.failures.isEmpty(), check.failures.joinToString("\n", "\n"))
        assertEquals(0, sql { it.fetchCount(org.jooq.impl.DSL.table("member_invitations"), org.jooq.impl.DSL.field("member_id").eq(member)) })
    }

    @Test
    fun malformedInputFromAnAuthorizedCallerIs400() = app {
        val check = Check()
        for (path in listOf("/api/v1/members/not-a-uuid/invitation", "/api/v1/members/123/invitation", "/api/v1/members/${"A".repeat(36)}/invitation")) {
            check.expect("create $path", send(HttpMethod.Post, path, "editor"), HttpStatusCode.BadRequest, "")
            check.expect("revoke $path", send(HttpMethod.Delete, path, "editor"), HttpStatusCode.BadRequest, "")
        }
        check.expect("unlink bad id", send(HttpMethod.Delete, "/api/v1/members/not-a-uuid/account-link", "manager"), HttpStatusCode.BadRequest, "")
        val secret = "A".repeat(43)
        for ((label, body, type) in listOf(
            Triple("text/plain", """{"secret":"$secret"}""", "text/plain"), Triple("no content type", """{"secret":"$secret"}""", null),
            Triple("empty body", "", "application/json"), Triple("truncated JSON", "{\"secret\":", "application/json"),
            Triple("JSON array", "[]", "application/json"), Triple("JSON null", "null", "application/json"),
            Triple("missing secret", "{}", "application/json"), Triple("numeric secret", """{"secret":1}""", "application/json"),
            Triple("null secret", """{"secret":null}""", "application/json"), Triple("unknown key", """{"secret":"$secret","role":"ADMIN"}""", "application/json"),
            Triple("oversized body", """{"secret":"$secret","x":"${"a".repeat(5000)}"}""", "application/json"))) {
            check.expect("accept $label", send(HttpMethod.Post, accept, "invitee", body, contentType = type), HttpStatusCode.BadRequest, "")
        }
        assertTrue(check.failures.isEmpty(), check.failures.joinToString("\n", "\n"))
    }

    @Test
    fun theWholeFlowWorksThroughRealSessionsAndEveryAcceptFailureLooksTheSame() = app {
        val member = adultMember()
        val created = send(HttpMethod.Post, invitation(member), "editor")
        assertEquals(HttpStatusCode.Created, created.status)
        val json = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val secret = json["secret"]!!.jsonPrimitive.content
        assertEquals(43, secret.length)
        assertTrue(json["expiresAt"]!!.jsonPrimitive.content.endsWith("Z"))
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, invitation(member), "editor").status, "one open invitation")

        // Every way to fail to accept is the same status and the same body.
        val reference = send(HttpMethod.Post, accept, "invitee", """{"secret":"${"B".repeat(43)}"}""")
        assertEquals(HttpStatusCode.BadRequest, reference.status)
        val referenceBody = reference.bodyAsText()
        assertEquals("""{"code":"invalid"}""", referenceBody)
        for ((label, handle, text) in listOf(
            Triple("an unverified account with the right secret", "stranger", secret),
            Triple("an account that already has a member", "plain", secret),
            Triple("a malformed secret", "invitee", "short"),
            Triple("a secret that is not base64url", "invitee", "!".repeat(43)))) {
            val response = send(HttpMethod.Post, accept, handle, """{"secret":"$text"}""")
            assertEquals(HttpStatusCode.BadRequest, response.status, label)
            assertEquals(referenceBody, response.bodyAsText(), label)
            assertFalse(response.bodyAsText().contains(text), "$label: the secret is never reflected")
        }
        assertNull(sql { it.fetchValue("SELECT account_id FROM members WHERE id = ?", member) }, "refusals changed nothing")

        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Post, accept, "invitee", """{"secret":"$secret"}""").status)
        assertEquals(accounts.getValue("invitee"), sql { it.fetchValue("SELECT account_id FROM members WHERE id = ?", member) })
        assertEquals("MEMBER", sql { it.fetchValue("SELECT role FROM members WHERE id = ?", member) })
        // Replay by anyone is the same refusal.
        val replay = send(HttpMethod.Post, accept, "second", """{"secret":"$secret"}""")
        assertEquals(HttpStatusCode.BadRequest, replay.status)
        assertEquals(referenceBody, replay.bodyAsText())

        // The linked account can no longer be invited over; unlinking frees the seat and ends its sessions.
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, invitation(member), "editor").status, "now linked")
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Delete, unlink(member), "editor").status, "EDIT_MEMBERS cannot unlink")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, unlink(member), "manager").status)
        assertNull(sql { it.fetchValue("SELECT account_id FROM members WHERE id = ?", member) })
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Delete, unlink(member), "manager").status, "nothing left to unlink")

        // Re-invite, revoke, and the revoked secret is dead.
        val again = Json.parseToJsonElement(send(HttpMethod.Post, invitation(member), "editor").bodyAsText())
            .jsonObject["secret"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, invitation(member), "editor").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, invitation(member), "editor").status, "idempotent")
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, accept, "second", """{"secret":"$again"}""").status)
        // Unknown members.
        val unknown = UUID.randomUUID()
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, invitation(unknown), "editor").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Delete, invitation(unknown), "editor").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Delete, unlink(unknown), "manager").status)
        // An administrator is not invited and not unlinked over HTTP.
        val admin = sql { it.fetchValue("SELECT id FROM members WHERE account_id = ?", accounts.getValue("editor")) } as UUID
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, invitation(admin), "editor").status)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Delete, unlink(admin), "manager").status)
    }

    @Test
    fun acceptanceIsRateLimitedWithRetryAfterBeforeAnyDatabaseWork() = app(AuthAdmissionLimiter(invitationLimit = 3)) {
        val body = """{"secret":"${"C".repeat(43)}"}"""
        repeat(3) { assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, accept, "invitee", body).status) }
        val limited = send(HttpMethod.Post, accept, "invitee", body)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
        assertEquals("no-store", limited.headers[HttpHeaders.CacheControl])
        assertEquals("""{"code":"rate_limited"}""", limited.bodyAsText())
        // The budget is only for acceptance: other routes are unaffected.
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, invitation(UUID.randomUUID()), "editor").status)
    }
}
