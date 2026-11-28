package org.openclub.api.teams

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import org.openclub.api.checkContract

/** Malformed-input and privilege-escalation matrix for the team, roster and assignment routes (#24). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamHttpInputAndEscalationTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val tag = UUID.randomUUID().toString().take(8)
    private val managerAccount = UUID.randomUUID()
    private val coachAccount = UUID.randomUUID()
    private val viewerAccount = UUID.randomUUID()
    private val outsiderAccount = UUID.randomUUID()
    private val manager = UUID.randomUUID()
    private val coach = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val outsider = UUID.randomUUID()
    private val plainMember = UUID.randomUUID()
    private val archived = UUID.randomUUID()
    private val teamA = UUID.randomUUID()
    private val teamB = UUID.randomUUID()
    private val teamNames = mapOf(teamA to "Tmatrix A $tag", teamB to "Tmatrix B $tag")
    private val allMembers = arrayOf(manager, coach, viewer, outsider, plainMember, archived)
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = when (handle) {
            "manager" -> ActiveSession(managerAccount, csrf)
            "coach" -> ActiveSession(coachAccount, csrf)
            "viewer" -> ActiveSession(viewerAccount, csrf)
            "outsider" -> ActiveSession(outsiderAccount, csrf)
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
        fun member(tx: DSLContext, id: UUID, account: UUID?) {
            if (account != null) {
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                    UUID.randomUUID(), account, UUID.randomUUID().toString())
            }
            tx.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth,
                   email, phone) VALUES (?, ?, 'Synthetic', 'Person', DATE '1990-01-01',
                   'person@example.invalid', '+41000000000')""", id, account)
        }
        try {
            runBlocking {
                db.onDatabaseThread { tx ->
                    tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                        club, "team-input-$club")
                    member(tx, manager, managerAccount)
                    member(tx, coach, coachAccount)
                    member(tx, viewer, viewerAccount)
                    member(tx, outsider, outsiderAccount)
                    member(tx, plainMember, null)
                    member(tx, archived, null)
                    tx.execute("UPDATE members SET archived_at = now() WHERE id = ?", archived)
                    for ((id, name) in teamNames) tx.execute("INSERT INTO teams (id, name) VALUES (?, ?)", id, name)
                    // manager: club-wide team management; coach: directory + one managed team;
                    // viewer: one viewed team and no directory grant; outsider: nothing.
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_TEAMS')", manager)
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", manager)
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", coach)
                    tx.execute("""INSERT INTO team_capability_grants (team_id, member_id, capability)
                        VALUES (?, ?, 'MANAGE_TEAMS')""", teamA, coach)
                    tx.execute("""INSERT INTO team_capability_grants (team_id, member_id, capability)
                        VALUES (?, ?, 'VIEW_TEAMS')""", teamA, viewer)
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
                tx.execute("DELETE FROM teams WHERE name LIKE ?", "Tmatrix % $tag")
                tx.execute("DELETE FROM members WHERE id IN (?, ?, ?, ?, ?, ?)", *allMembers)
                tx.execute("DELETE FROM accounts WHERE id IN (?, ?, ?, ?)",
                    managerAccount, coachAccount, viewerAccount, outsiderAccount)
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    /** No test in this class may rename, create or delete a team, assign anyone, or change a grant. */
    @AfterEach
    fun assertNothingChanged() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                val names = tx.fetch("SELECT id, name FROM teams WHERE id IN (?, ?)", teamA, teamB)
                    .associate { it.get("id", UUID::class.java) to it.get("name", String::class.java) }
                assertEquals(teamNames, names, "team names")
                assertEquals(2L, tx.fetchValue("SELECT COUNT(*) FROM teams WHERE name LIKE ?", "Tmatrix % $tag")
                    ?.toString()?.toLong(), "teams created")
                assertEquals(0L, tx.fetchValue(
                    "SELECT COUNT(*) FROM member_team_assignments WHERE team_id IN (?, ?)", teamA, teamB)
                    ?.toString()?.toLong(), "assignments")
                assertEquals(3L, tx.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id IN (?, ?, ?, ?, ?, ?)",
                    *allMembers)?.toString()?.toLong(), "club grants")
                assertEquals(2L, tx.fetchValue(
                    "SELECT COUNT(*) FROM team_capability_grants WHERE member_id IN (?, ?, ?, ?, ?, ?)",
                    *allMembers)?.toString()?.toLong(), "team grants")
            }
        }
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { checkContract();
            module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin,
                database = requireNotNull(database))
        }
        block()
    }

    /** Runs every case and reports all mismatches together, so one failure never hides another. */
    private inner class Cases(private val app: ApplicationTestBuilder) {
        private val failures = mutableListOf<String>()

        suspend fun send(
            expected: HttpStatusCode,
            label: String,
            method: HttpMethod,
            path: String,
            body: String? = null,
            handle: String = "manager",
            contentType: String? = "application/json",
        ) {
            val response = app.client.request(path) {
                this.method = method
                header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
                if (method != HttpMethod.Get) {
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }
                if (contentType != null && body != null) header(HttpHeaders.ContentType, contentType)
                if (body != null) setBody(body)
            }
            val text = response.bodyAsText()
            val name = "$label (${method.value} $path)"
            if (response.status != expected) failures += "$name: expected $expected but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$name: missing no-store"
            if (text.contains("person@example.invalid")) failures += "$name: leaked contact data"
        }

        fun assertNoFailures() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    /** Bodies that must be rejected as malformed before any team is created, renamed or deleted. */
    private fun malformedBodies(): List<Triple<String, String?, String?>> {
        val ok = "application/json"
        return listOf(
            Triple("text/plain", """{"name":"Tmatrix X $tag"}""", "text/plain"),
            Triple("no content type", """{"name":"Tmatrix X $tag"}""", null),
            Triple("empty body", "", ok),
            Triple("truncated JSON", "{\"name\":", ok),
            Triple("JSON array", "[]", ok),
            Triple("JSON null", "null", ok),
            Triple("missing name", "{}", ok),
            Triple("unknown key", """{"name":"Tmatrix X $tag","id":"${UUID.randomUUID()}"}""", ok),
            Triple("numeric name", """{"name":1}""", ok),
            Triple("null name", """{"name":null}""", ok),
            Triple("blank name", """{"name":"   "}""", ok),
            Triple("201-character name", "{\"name\":\"" + "a".repeat(201) + "\"}", ok),
            Triple("oversized body", "{\"name\":\"Tmatrix X $tag\",\"x\":\"" + "a".repeat(5000) + "\"}", ok),
        )
    }

    @Test
    fun malformedTeamRequestsFromAManagerAre400() = app {
        val c = Cases(this)
        val bad = HttpStatusCode.BadRequest
        for ((label, body, type) in malformedBodies()) {
            c.send(bad, "create: $label", HttpMethod.Post, "/api/v1/teams", body, contentType = type)
            c.send(bad, "rename: $label", HttpMethod.Patch, "/api/v1/teams/$teamA", body, contentType = type)
            // Delete takes the exact current name, so a blank or over-long name is just a mismatch.
            if (label != "blank name" && label != "201-character name") {
                c.send(bad, "delete: $label", HttpMethod.Delete, "/api/v1/teams/$teamA", body, contentType = type)
            }
        }
        val ok = """{"name":"Tmatrix X $tag"}"""
        c.send(bad, "rename non-UUID team", HttpMethod.Patch, "/api/v1/teams/not-a-uuid", ok)
        c.send(bad, "delete non-UUID team", HttpMethod.Delete, "/api/v1/teams/not-a-uuid", ok)
        c.send(bad, "delete with a stale name", HttpMethod.Delete, "/api/v1/teams/$teamA",
            """{"name":"Tmatrix Old $tag"}""")
        // Unknown team is not a validation error for an authorized caller.
        c.send(HttpStatusCode.NotFound, "rename unknown team", HttpMethod.Patch,
            "/api/v1/teams/${UUID.randomUUID()}", ok)
        c.send(HttpStatusCode.NotFound, "delete unknown team", HttpMethod.Delete,
            "/api/v1/teams/${UUID.randomUUID()}", ok)
        c.assertNoFailures()
    }

    /** Without club-wide MANAGE_TEAMS the caller must not learn the expected shape: 403, never 400. */
    @Test
    fun malformedTeamRequestsFromCallersWithoutClubManagementAre403() = app {
        val c = Cases(this)
        val denied = HttpStatusCode.Forbidden
        // A team-scoped manager (coach) still cannot create, rename or delete teams.
        for (handle in listOf("outsider", "viewer", "coach")) {
            for ((label, body, type) in malformedBodies()) {
                c.send(denied, "$handle create: $label", HttpMethod.Post, "/api/v1/teams", body, handle, type)
                c.send(denied, "$handle rename: $label", HttpMethod.Patch, "/api/v1/teams/$teamA", body, handle, type)
                c.send(denied, "$handle delete: $label", HttpMethod.Delete, "/api/v1/teams/$teamA", body, handle, type)
            }
            c.send(denied, "$handle rename non-UUID team", HttpMethod.Patch, "/api/v1/teams/not-a-uuid",
                """{"name":"Tmatrix X $tag"}""", handle)
            c.send(denied, "$handle delete non-UUID team", HttpMethod.Delete, "/api/v1/teams/not-a-uuid",
                """{"name":"Tmatrix X $tag"}""", handle)
            c.send(denied, "$handle valid create", HttpMethod.Post, "/api/v1/teams",
                """{"name":"Tmatrix X $tag"}""", handle)
            c.send(denied, "$handle valid rename", HttpMethod.Patch, "/api/v1/teams/$teamA",
                """{"name":"Tmatrix X $tag"}""", handle)
            c.send(denied, "$handle valid delete", HttpMethod.Delete, "/api/v1/teams/$teamA",
                """{"name":"${teamNames.getValue(teamA)}"}""", handle)
        }
        c.send(HttpStatusCode.Unauthorized, "unknown session create", HttpMethod.Post, "/api/v1/teams",
            """{"name":"Tmatrix X $tag"}""", "ghost")
        c.assertNoFailures()
    }

    @Test
    fun assignmentRoutesValidateInputOnlyAfterAuthorization() = app {
        val c = Cases(this)
        val denied = HttpStatusCode.Forbidden
        val bad = HttpStatusCode.BadRequest
        for (method in listOf(HttpMethod.Post, HttpMethod.Delete)) {
            fun path(team: Any, member: Any) = "/api/v1/teams/$team/members/$member"
            // Authorized somewhere (club-wide manager, team-scoped coach): malformed ids are 400.
            for (handle in listOf("manager", "coach")) {
                c.send(bad, "$handle non-UUID team", method, path("nope", plainMember), handle = handle)
                c.send(bad, "$handle non-UUID member", method, path(teamA, "nope"), handle = handle)
                c.send(bad, "$handle numeric ids", method, path(1, 2), handle = handle)
            }
            // No team-management grant anywhere: 403 for malformed and valid ids alike.
            for (handle in listOf("outsider", "viewer")) {
                c.send(denied, "$handle non-UUID team", method, path("nope", plainMember), handle = handle)
                c.send(denied, "$handle non-UUID member", method, path(teamA, "nope"), handle = handle)
                c.send(denied, "$handle valid ids", method, path(teamA, plainMember), handle = handle)
            }
            // Cross-team boundary: the coach manages only team A.
            c.send(denied, "coach on a team they do not manage", method, path(teamB, plainMember), handle = "coach")
            // The manager cannot distinguish an unknown team from a forbidden one.
            c.send(denied, "manager on an unknown team", method, path(UUID.randomUUID(), plainMember))
            c.send(denied, "coach on an unknown team", method, path(UUID.randomUUID(), plainMember), handle = "coach")
            c.send(HttpStatusCode.Unauthorized, "unknown session", method, path(teamA, plainMember), handle = "ghost")
        }
        // Authorized but nothing to change: assigning an unknown or archived member conflicts,
        // unassigning someone who is not on the team is not found.
        c.send(HttpStatusCode.Conflict, "assign unknown member", HttpMethod.Post,
            "/api/v1/teams/$teamA/members/${UUID.randomUUID()}")
        c.send(HttpStatusCode.Conflict, "assign archived member", HttpMethod.Post,
            "/api/v1/teams/$teamA/members/$archived")
        c.send(HttpStatusCode.NotFound, "unassign member who is not assigned", HttpMethod.Delete,
            "/api/v1/teams/$teamA/members/$plainMember")
        c.assertNoFailures()
    }

    @Test
    fun rosterValidatesInputOnlyAfterAuthorization() = app {
        val c = Cases(this)
        val bad = HttpStatusCode.BadRequest
        val denied = HttpStatusCode.Forbidden
        fun roster(team: Any, query: String = "") = "/api/v1/teams/$team/members$query"
        // The manager and the coach (directory grant plus team A) may read team A's roster.
        for (handle in listOf("manager", "coach")) {
            c.send(HttpStatusCode.OK, "$handle reads team A", HttpMethod.Get, roster(teamA), handle = handle)
            c.send(bad, "$handle non-UUID team", HttpMethod.Get, roster("nope"), handle = handle)
            c.send(bad, "$handle limit 0", HttpMethod.Get, roster(teamA, "?limit=0"), handle = handle)
            c.send(bad, "$handle limit 51", HttpMethod.Get, roster(teamA, "?limit=51"), handle = handle)
            c.send(bad, "$handle non-numeric limit", HttpMethod.Get, roster(teamA, "?limit=abc"), handle = handle)
            c.send(bad, "$handle garbage cursor", HttpMethod.Get, roster(teamA, "?cursor=garbage"), handle = handle)
        }
        // Without the directory grant, bad and good requests alike are 403, never 400.
        for (handle in listOf("outsider", "viewer")) {
            c.send(denied, "$handle valid roster", HttpMethod.Get, roster(teamA), handle = handle)
            c.send(denied, "$handle non-UUID team", HttpMethod.Get, roster("nope"), handle = handle)
            c.send(denied, "$handle limit 0", HttpMethod.Get, roster(teamA, "?limit=0"), handle = handle)
            c.send(denied, "$handle garbage cursor", HttpMethod.Get, roster(teamA, "?cursor=garbage"), handle = handle)
        }
        // Cross-team boundary and unknown team: the coach has no grant on team B.
        c.send(denied, "coach reads another team", HttpMethod.Get, roster(teamB), handle = "coach")
        c.send(denied, "coach reads an unknown team", HttpMethod.Get, roster(UUID.randomUUID()), handle = "coach")
        c.send(denied, "manager reads an unknown team", HttpMethod.Get, roster(UUID.randomUUID()))
        c.send(HttpStatusCode.Unauthorized, "unknown session", HttpMethod.Get, roster(teamA), handle = "ghost")
        c.assertNoFailures()
    }
}
