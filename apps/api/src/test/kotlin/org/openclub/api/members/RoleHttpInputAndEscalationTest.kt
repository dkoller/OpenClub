package org.openclub.api.members

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.module
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID

/** Malformed-input and privilege-escalation matrix for the member role routes (#24). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleHttpInputAndEscalationTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val managerAccount = UUID.randomUUID()
    private val coachAccount = UUID.randomUUID()
    private val adminNoGrantAccount = UUID.randomUUID()
    private val manager = UUID.randomUUID()
    private val coach = UUID.randomUUID()
    private val adminNoGrant = UUID.randomUUID()
    private val plainMember = UUID.randomUUID()
    private val archived = UUID.randomUUID()
    private val expectedRoles = mapOf(
        manager to "ADMIN", coach to "MEMBER", adminNoGrant to "ADMIN", plainMember to "MEMBER", archived to "MEMBER",
    )
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = when (handle) {
            "manager" -> ActiveSession(managerAccount, csrf)
            "coach" -> ActiveSession(coachAccount, csrf)
            "adminnogrant" -> ActiveSession(adminNoGrantAccount, csrf)
            else -> null
        }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    @BeforeAll
    fun createFixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val db = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        database = db
        fun member(tx: DSLContext, id: UUID, account: UUID?, role: String) {
            if (account != null) {
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                    UUID.randomUUID(), account, UUID.randomUUID().toString())
            }
            tx.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth,
                   email, phone, role) VALUES (?, ?, 'Synthetic', 'Person', DATE '1990-01-01',
                   'person@example.invalid', '+41000000000', ?)""", id, account, role)
        }
        try {
            runBlocking {
                db.onDatabaseThread { tx ->
                    tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                        club, "role-input-$club")
                    member(tx, manager, managerAccount, "ADMIN")
                    member(tx, coach, coachAccount, "MEMBER")
                    member(tx, adminNoGrant, adminNoGrantAccount, "ADMIN")
                    member(tx, plainMember, null, "MEMBER")
                    member(tx, archived, null, "MEMBER")
                    tx.execute("UPDATE members SET archived_at = now() WHERE id = ?", archived)
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", manager)
                }
            }
        } catch (failure: Throwable) {
            dropFixture()
            throw failure
        }
    }

    @AfterAll
    fun dropFixture() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM club_capability_grants WHERE member_id = ?", manager)
                tx.execute("DELETE FROM members WHERE id IN (?, ?, ?, ?, ?)",
                    manager, coach, adminNoGrant, plainMember, archived)
                tx.execute("DELETE FROM accounts WHERE id IN (?, ?, ?)",
                    managerAccount, coachAccount, adminNoGrantAccount)
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    /** No test in this class may change a role or the seeded permission-manager grant. */
    @AfterEach
    fun assertNothingChanged() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                val roles = tx.fetch("SELECT id, role FROM members WHERE id IN (?, ?, ?, ?, ?)",
                    *expectedRoles.keys.toTypedArray())
                    .associate { it.get("id", UUID::class.java) to it.get("role", String::class.java) }
                assertEquals(expectedRoles, roles, "roles")
                assertEquals(1L, tx.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id IN (?, ?, ?, ?, ?)",
                    *expectedRoles.keys.toTypedArray())?.toString()?.toLong(), "grants")
                assertEquals(1L, tx.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id = ? " +
                        "AND capability = 'MANAGE_PERMISSIONS'", manager)?.toString()?.toLong(), "manager grant")
            }
        }
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin,
                database = requireNotNull(database))
        }
        block()
    }

    /** Runs every case and reports all mismatches together, so one failure never hides another. */
    private inner class Cases(private val app: ApplicationTestBuilder) {
        private val failures = mutableListOf<String>()

        suspend fun update(
            expected: HttpStatusCode,
            label: String,
            path: String,
            body: String?,
            handle: String = "manager",
            contentType: String? = "application/json",
        ) {
            record("$label (PATCH)", expected, app.client.patch("/api/v1/members/$path/role") {
                apply(this, handle, contentType, body)
            })
        }

        suspend fun read(expected: HttpStatusCode, label: String, path: String, handle: String = "manager") {
            record("$label (GET)", expected, app.client.get("/api/v1/members/$path/role") {
                header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            })
        }

        private fun apply(builder: HttpRequestBuilder, handle: String, contentType: String?, body: String?) {
            builder.header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            builder.header(HttpHeaders.Origin, origin)
            builder.header("X-CSRF-Token", csrf)
            if (contentType != null) builder.header(HttpHeaders.ContentType, contentType)
            if (body != null) builder.setBody(body)
        }

        private suspend fun record(label: String, expected: HttpStatusCode, response: HttpResponse) {
            if (response.status != expected) failures += "$label: expected $expected but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
            if (response.bodyAsText().contains("person@example.invalid")) failures += "$label: leaked contact data"
        }

        fun assertNoFailures() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    @Test
    fun malformedPatchRequestsAre400() = app {
        val c = Cases(this)
        val bad = HttpStatusCode.BadRequest
        val ok = """{"role":"ADMIN"}"""
        c.update(bad, "text/plain", "$plainMember", ok, contentType = "text/plain")
        c.update(bad, "no content type", "$plainMember", ok, contentType = null)
        c.update(bad, "empty body", "$plainMember", "")
        c.update(bad, "truncated JSON", "$plainMember", "{\"role\":")
        c.update(bad, "JSON array", "$plainMember", "[]")
        c.update(bad, "JSON null", "$plainMember", "null")
        c.update(bad, "missing role", "$plainMember", "{}")
        c.update(bad, "unknown key", "$plainMember", """{"role":"ADMIN","accountId":"forged"}""")
        c.update(bad, "unknown role", "$plainMember", """{"role":"OWNER"}""")
        c.update(bad, "lowercase role", "$plainMember", """{"role":"admin"}""")
        c.update(bad, "numeric role", "$plainMember", """{"role":1}""")
        c.update(bad, "null role", "$plainMember", """{"role":null}""")
        c.update(bad, "oversized body", "$plainMember", "{\"role\":\"ADMIN\",\"x\":\"" + "a".repeat(5000) + "\"}")
        c.update(bad, "non-UUID member", "not-a-uuid", ok)
        c.assertNoFailures()
    }

    @Test
    fun readsRejectBadPathsOnlyAfterAuthorization() = app {
        val c = Cases(this)
        c.read(HttpStatusCode.OK, "manager reading a member", "$plainMember")
        c.read(HttpStatusCode.BadRequest, "manager with malformed member", "not-a-uuid")
        // Callers without MANAGE_PERMISSIONS get 403 for bad and good paths alike, never 400.
        for (handle in listOf("adminnogrant", "coach")) {
            c.read(HttpStatusCode.Forbidden, "$handle with malformed member", "not-a-uuid", handle)
            c.read(HttpStatusCode.Forbidden, "$handle with valid member", "$plainMember", handle)
        }
        c.read(HttpStatusCode.Unauthorized, "unknown session", "$plainMember", "ghost")
        // Unknown and archived members are indistinguishable from forbidden on read.
        c.read(HttpStatusCode.Forbidden, "manager reading an unknown member", "${UUID.randomUUID()}")
        c.read(HttpStatusCode.Forbidden, "manager reading an archived member", "$archived")
        c.assertNoFailures()
    }

    @Test
    fun escalationAndUnknownTargetsAre403() = app {
        val c = Cases(this)
        val denied = HttpStatusCode.Forbidden
        c.update(denied, "unknown member", "${UUID.randomUUID()}", """{"role":"ADMIN"}""")
        c.update(denied, "archived member", "$archived", """{"role":"ADMIN"}""")
        c.update(denied, "self demotion", "$manager", """{"role":"MEMBER"}""")
        c.update(denied, "self promotion no-op", "$manager", """{"role":"ADMIN"}""")
        c.update(denied, "ADMIN without MANAGE_PERMISSIONS", "$plainMember", """{"role":"ADMIN"}""", "adminnogrant")
        c.update(denied, "MEMBER promoting self", "$coach", """{"role":"ADMIN"}""", "coach")
        c.update(denied, "MEMBER promoting another", "$plainMember", """{"role":"ADMIN"}""", "coach")
        c.update(HttpStatusCode.Unauthorized, "unknown session", "$plainMember", """{"role":"ADMIN"}""", "ghost")
        c.assertNoFailures()
    }

    /** Callers without MANAGE_PERMISSIONS must not learn the expected shape: 403, never 400. */
    @Test
    fun malformedPatchesFromUnauthorizedCallersAre403() = app {
        val c = Cases(this)
        val denied = HttpStatusCode.Forbidden
        for (handle in listOf("adminnogrant", "coach")) {
            c.update(denied, "$handle empty body", "$plainMember", "", handle)
            c.update(denied, "$handle truncated JSON", "$plainMember", "{\"role\":", handle)
            c.update(denied, "$handle unknown role", "$plainMember", """{"role":"OWNER"}""", handle)
            c.update(denied, "$handle unknown key", "$plainMember", """{"role":"ADMIN","x":1}""", handle)
            c.update(denied, "$handle text/plain", "$plainMember", """{"role":"ADMIN"}""", handle, "text/plain")
            c.update(denied, "$handle malformed member", "not-a-uuid", """{"role":"ADMIN"}""", handle)
        }
        c.assertNoFailures()
    }
}
