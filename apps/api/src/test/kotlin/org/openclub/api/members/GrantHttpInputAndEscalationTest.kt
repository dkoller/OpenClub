package org.openclub.api.members

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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

/** Malformed-input and privilege-escalation matrix for the grant routes (#24). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrantHttpInputAndEscalationTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val managerAccount = UUID.randomUUID()
    private val coachAccount = UUID.randomUUID()
    private val adminNoGrantAccount = UUID.randomUUID()
    private val unverifiedAdminAccount = UUID.randomUUID()
    private val manager = UUID.randomUUID()
    private val coach = UUID.randomUUID()
    private val adminNoGrant = UUID.randomUUID()
    private val unverifiedAdmin = UUID.randomUUID()
    private val plainMember = UUID.randomUUID()
    private val archived = UUID.randomUUID()
    private val team = UUID.randomUUID()
    private val allMembers = arrayOf(manager, coach, adminNoGrant, unverifiedAdmin, plainMember, archived)
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
        fun member(tx: DSLContext, id: UUID, account: UUID?, role: String, verified: Boolean) {
            if (account != null) {
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                if (verified) tx.execute(
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
                        club, "grant-input-$club")
                    member(tx, manager, managerAccount, "ADMIN", true)
                    member(tx, coach, coachAccount, "MEMBER", true)
                    member(tx, adminNoGrant, adminNoGrantAccount, "ADMIN", true)
                    member(tx, unverifiedAdmin, unverifiedAdminAccount, "ADMIN", false)
                    member(tx, plainMember, null, "MEMBER", false)
                    member(tx, archived, null, "MEMBER", false)
                    tx.execute("UPDATE members SET archived_at = now() WHERE id = ?", archived)
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", manager)
                    tx.execute("INSERT INTO teams (id, name) VALUES (?, 'Juniors')", team)
                    tx.execute("""INSERT INTO team_capability_grants (team_id, member_id, capability)
                        VALUES (?, ?, 'MANAGE_TEAMS')""", team, coach)
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
                tx.execute("DELETE FROM teams WHERE id = ?", team)
                tx.execute("DELETE FROM members WHERE id IN (?, ?, ?, ?, ?, ?)", *allMembers)
                tx.execute("DELETE FROM accounts WHERE id IN (?, ?, ?, ?)",
                    managerAccount, coachAccount, adminNoGrantAccount, unverifiedAdminAccount)
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    /** No test in this class may leave a grant behind: only the two seeded grants may exist. */
    @AfterEach
    fun assertNothingPersisted() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                assertEquals(1L, tx.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id IN (?, ?, ?, ?, ?, ?)",
                    *allMembers)?.toString()?.toLong(), "club grants")
                assertEquals(1L, tx.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id = ? " +
                        "AND capability = 'MANAGE_PERMISSIONS'", manager)?.toString()?.toLong(), "manager grant")
                assertEquals(1L, tx.fetchValue(
                    "SELECT COUNT(*) FROM team_capability_grants WHERE member_id IN (?, ?, ?, ?, ?, ?)",
                    *allMembers)?.toString()?.toLong(), "team grants")
                assertEquals(1L, tx.fetchValue(
                    "SELECT COUNT(*) FROM team_capability_grants WHERE team_id = ? AND member_id = ?",
                    team, coach)?.toString()?.toLong(), "coach team grant")
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

    private fun grant(
        member: Any = plainMember.toString(),
        capability: Any = "VIEW_MEMBER_DIRECTORY",
        scope: Any = "CLUB",
        team: String? = null,
    ) = buildString {
        fun v(x: Any) = if (x is String) "\"$x\"" else x.toString()
        append("{\"memberId\":${v(member)},\"capability\":${v(capability)},\"scope\":${v(scope)}")
        if (team != null) append(",\"teamId\":$team")
        append("}")
    }

    /** Runs every case and reports all mismatches together, so one failure never hides another. */
    private inner class Cases(private val app: ApplicationTestBuilder) {
        private val failures = mutableListOf<String>()

        suspend fun mutation(
            expected: HttpStatusCode,
            label: String,
            body: String?,
            handle: String = "manager",
            contentType: String? = "application/json",
            verbs: List<Boolean> = listOf(true, false),
        ) {
            for (post in verbs) {
                val block: HttpRequestBuilder.() -> Unit = {
                    header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    if (contentType != null) header(HttpHeaders.ContentType, contentType)
                    if (body != null) setBody(body)
                }
                val response =
                    if (post) app.client.post("/api/v1/grants", block) else app.client.delete("/api/v1/grants", block)
                record("$label (${if (post) "POST" else "DELETE"})", expected, response)
            }
        }

        suspend fun read(expected: HttpStatusCode, label: String, query: String, handle: String = "manager") {
            record(label, expected, app.client.get("/api/v1/grants$query") {
                header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            })
        }

        private suspend fun record(label: String, expected: HttpStatusCode, response: HttpResponse) {
            if (response.status != expected) failures += "$label: expected $expected but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
            if (response.bodyAsText().contains("person@example.invalid")) failures += "$label: leaked contact data"
        }

        fun assertNoFailures() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    @Test
    fun malformedMutationBodiesAre400() = app {
        val c = Cases(this)
        val bad = HttpStatusCode.BadRequest
        c.mutation(bad, "text/plain", grant(), contentType = "text/plain")
        c.mutation(bad, "no content type", grant(), contentType = null)
        c.mutation(bad, "empty body", "")
        c.mutation(bad, "truncated JSON", "{\"memberId\":")
        c.mutation(bad, "JSON array", "[]")
        c.mutation(bad, "JSON null", "null")
        c.mutation(bad, "unknown key", grant().dropLast(1) + ",\"role\":\"ADMIN\"}")
        c.mutation(bad, "missing scope",
            "{\"memberId\":\"$plainMember\",\"capability\":\"VIEW_MEMBER_DIRECTORY\"}")
        c.mutation(bad, "non-UUID member", grant(member = "not-a-uuid"))
        c.mutation(bad, "numeric member", grant(member = 7))
        c.mutation(bad, "unknown capability", grant(capability = "ROOT"))
        c.mutation(bad, "lowercase capability", grant(capability = "edit_members"))
        c.mutation(bad, "numeric capability", grant(capability = 1))
        c.mutation(bad, "unknown scope", grant(scope = "GLOBAL"))
        c.mutation(bad, "TEAM scope without team", grant(scope = "TEAM", capability = "VIEW_TEAMS"))
        c.mutation(bad, "CLUB scope with team", grant(team = "\"$team\""))
        c.mutation(bad, "null teamId", grant(scope = "TEAM", capability = "VIEW_TEAMS", team = "null"))
        c.mutation(bad, "non-UUID teamId", grant(scope = "TEAM", capability = "VIEW_TEAMS", team = "\"nope\""))
        c.mutation(bad, "team-scoped MANAGE_PERMISSIONS",
            grant(scope = "TEAM", capability = "MANAGE_PERMISSIONS", team = "\"$team\""))
        c.mutation(bad, "team-scoped EDIT_MEMBERS",
            grant(scope = "TEAM", capability = "EDIT_MEMBERS", team = "\"$team\""))
        c.mutation(bad, "CLUB scope with null teamId", grant(team = "null"))
        c.mutation(bad, "oversized body", grant().dropLast(1) + ",\"x\":\"" + "a".repeat(5000) + "\"}")
        c.assertNoFailures()
    }

    @Test
    fun readRejectsBadQueriesOnlyAfterAuthorization() = app {
        val c = Cases(this)
        c.read(HttpStatusCode.BadRequest, "manager without memberId", "")
        c.read(HttpStatusCode.BadRequest, "manager with malformed memberId", "?memberId=nope")
        c.read(HttpStatusCode.BadRequest, "manager with empty memberId", "?memberId=")
        c.read(HttpStatusCode.BadRequest, "manager with repeated memberId",
            "?memberId=$plainMember&memberId=$archived")
        c.read(HttpStatusCode.BadRequest, "manager with repeated identical memberId",
            "?memberId=$plainMember&memberId=$plainMember")
        // Callers without MANAGE_PERMISSIONS get 403 for bad and good queries alike, never 400.
        for (handle in listOf("adminnogrant", "coach")) {
            c.read(HttpStatusCode.Forbidden, "$handle without memberId", "", handle)
            c.read(HttpStatusCode.Forbidden, "$handle with malformed memberId", "?memberId=nope", handle)
            c.read(HttpStatusCode.Forbidden, "$handle with valid memberId", "?memberId=$plainMember", handle)
            c.read(HttpStatusCode.Forbidden, "$handle with repeated memberId",
                "?memberId=$plainMember&memberId=$archived", handle)
        }
        c.read(HttpStatusCode.Unauthorized, "unknown session", "?memberId=$plainMember", "ghost")
        // Reads do not distinguish "no grants" from "no such member": both are an empty 200.
        c.read(HttpStatusCode.OK, "manager reading an unknown member", "?memberId=${UUID.randomUUID()}")
        c.assertNoFailures()
    }

    /**
     * The service rejects unknown, archived and unknown-team targets before it checks whether the
     * grant exists, so those are 403 for DELETE as well as POST. Revoking a never-given
     * MANAGE_PERMISSIONS from an existing recipient is an idempotent 204, not an escalation, so
     * the recipient-eligibility cases use POST only.
     */
    @Test
    fun escalationAndUnknownTargetsAre403() = app {
        val c = Cases(this)
        val denied = HttpStatusCode.Forbidden
        c.mutation(denied, "unknown member", grant(member = UUID.randomUUID().toString()))
        c.mutation(denied, "archived recipient", grant(member = archived.toString()))
        c.mutation(denied, "unknown team",
            grant(scope = "TEAM", capability = "VIEW_TEAMS", team = "\"${UUID.randomUUID()}\""))
        c.mutation(denied, "self mutation", grant(member = manager.toString(), capability = "EDIT_MEMBERS"))
        c.mutation(denied, "MANAGE_PERMISSIONS to non-ADMIN",
            grant(capability = "MANAGE_PERMISSIONS"), verbs = listOf(true))
        c.mutation(denied, "MANAGE_PERMISSIONS to ADMIN without login",
            grant(member = unverifiedAdmin.toString(), capability = "MANAGE_PERMISSIONS"), verbs = listOf(true))
        val allowedBody = grant()
        c.mutation(denied, "ADMIN without MANAGE_PERMISSIONS grant", allowedBody, "adminnogrant")
        c.mutation(denied, "team MANAGE_TEAMS holder", allowedBody, "coach")
        c.mutation(HttpStatusCode.Unauthorized, "unknown session", allowedBody, "ghost")
        c.assertNoFailures()
    }

    /** Callers without MANAGE_PERMISSIONS must not learn the expected body shape: 403, never 400. */
    @Test
    fun malformedBodiesFromUnauthorizedCallersAre403() = app {
        val c = Cases(this)
        val denied = HttpStatusCode.Forbidden
        for (handle in listOf("adminnogrant", "coach")) {
            c.mutation(denied, "$handle empty body", "", handle)
            c.mutation(denied, "$handle truncated JSON", "{\"memberId\":", handle)
            c.mutation(denied, "$handle unknown capability", grant(capability = "ROOT"), handle)
            c.mutation(denied, "$handle unknown key", grant().dropLast(1) + ",\"role\":\"ADMIN\"}", handle)
            c.mutation(denied, "$handle text/plain", grant(), handle, contentType = "text/plain")
        }
        c.assertNoFailures()
    }
}
