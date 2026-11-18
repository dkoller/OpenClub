package org.openclub.api.members

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openclub.api.module
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID

/** Malformed-input and privilege-escalation matrix for the grant routes (#24). */
class GrantHttpInputAndEscalationTest {
    @Test
    fun malformedGrantRequestsAre400AndEscalationAttemptsAre403WithoutPersistingAnything() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        val managerAccount = UUID.randomUUID()
        val coachAccount = UUID.randomUUID()
        val adminNoGrantAccount = UUID.randomUUID()
        val manager = UUID.randomUUID()
        val coach = UUID.randomUUID()
        val adminNoGrant = UUID.randomUUID()
        val plainMember = UUID.randomUUID()
        val unverifiedAdmin = UUID.randomUUID()
        val unverifiedAdminAccount = UUID.randomUUID()
        val archived = UUID.randomUUID()
        val team = UUID.randomUUID()
        val origin = "http://localhost:5173"
        val csrf = "x".repeat(43)
        val cookies = AuthCookiePolicy.localDevelopment()
        val sessions = object : BrowserSessionOperations {
            override suspend fun read(handle: String?): ActiveSession? = when (handle) {
                "manager" -> ActiveSession(managerAccount, csrf)
                "coach" -> ActiveSession(coachAccount, csrf)
                "adminnogrant" -> ActiveSession(adminNoGrantAccount, csrf)
                else -> null
            }
            override suspend fun logout(handle: String?, csrfToken: String?) =
                LogoutResult.UNAUTHENTICATED
        }
        fun member(db: org.jooq.DSLContext, id: UUID, account: UUID?, role: String, verified: Boolean) {
            if (account != null) {
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                if (verified) db.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                    UUID.randomUUID(), account, UUID.randomUUID().toString())
            }
            db.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth,
                   email, phone, role) VALUES (?, ?, 'Synthetic', 'Person', DATE '1990-01-01',
                   'person@example.invalid', '+41000000000', ?)""", id, account, role)
        }
        try {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                        UUID.randomUUID(), "grant-input-" + UUID.randomUUID())
                    member(db, manager, managerAccount, "ADMIN", true)
                    member(db, coach, coachAccount, "MEMBER", true)
                    member(db, adminNoGrant, adminNoGrantAccount, "ADMIN", true)
                    member(db, unverifiedAdmin, unverifiedAdminAccount, "ADMIN", false)
                    member(db, plainMember, null, "MEMBER", false)
                    member(db, archived, null, "MEMBER", false)
                    db.execute("UPDATE members SET archived_at = now() WHERE id = ?", archived)
                    db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", manager)
                    db.execute("INSERT INTO teams (id, name) VALUES (?, 'Juniors')", team)
                    db.execute("""INSERT INTO team_capability_grants (team_id, member_id, capability)
                        VALUES (?, ?, 'MANAGE_TEAMS')""", team, coach)
                }
            }
            testApplication {
                application {
                    module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin,
                        database = database)
                }
                suspend fun send(
                    post: Boolean,
                    body: String?,
                    handle: String = "manager",
                    contentType: String? = "application/json",
                ): HttpResponse {
                    val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                        header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
                        header(HttpHeaders.Origin, origin)
                        header("X-CSRF-Token", csrf)
                        if (contentType != null) header(HttpHeaders.ContentType, contentType)
                        if (body != null) setBody(body)
                    }
                    return if (post) client.post("/api/v1/grants", block)
                    else client.delete("/api/v1/grants", block)
                }
                suspend fun assertStatus(expected: HttpStatusCode, label: String,
                                         body: String?, handle: String = "manager",
                                         contentType: String? = "application/json",
                                         postOnly: Boolean = false) {
                    for (post in if (postOnly) listOf(true) else listOf(true, false)) {
                        val response = send(post, body, handle, contentType)
                        assertEquals(expected, response.status,
                            "$label (${if (post) "POST" else "DELETE"})")
                        assertEquals("no-store", response.headers[HttpHeaders.CacheControl], label)
                        assertFalse(response.bodyAsText().contains("person@example.invalid"), label)
                    }
                }
                fun grant(
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

                // Malformed input: always 400, never 503 and never a persisted grant.
                assertStatus(HttpStatusCode.BadRequest, "text/plain", grant(), contentType = "text/plain")
                assertStatus(HttpStatusCode.BadRequest, "no content type", grant(), contentType = null)
                assertStatus(HttpStatusCode.BadRequest, "empty body", "")
                assertStatus(HttpStatusCode.BadRequest, "truncated JSON", "{\"memberId\":")
                assertStatus(HttpStatusCode.BadRequest, "JSON array", "[]")
                assertStatus(HttpStatusCode.BadRequest, "JSON null", "null")
                assertStatus(HttpStatusCode.BadRequest, "unknown key",
                    grant().dropLast(1) + ",\"role\":\"ADMIN\"}")
                assertStatus(HttpStatusCode.BadRequest, "missing scope",
                    "{\"memberId\":\"$plainMember\",\"capability\":\"VIEW_MEMBER_DIRECTORY\"}")
                assertStatus(HttpStatusCode.BadRequest, "non-UUID member", grant(member = "not-a-uuid"))
                assertStatus(HttpStatusCode.BadRequest, "numeric member", grant(member = 7))
                assertStatus(HttpStatusCode.BadRequest, "unknown capability", grant(capability = "ROOT"))
                assertStatus(HttpStatusCode.BadRequest, "lowercase capability",
                    grant(capability = "edit_members"))
                assertStatus(HttpStatusCode.BadRequest, "numeric capability", grant(capability = 1))
                assertStatus(HttpStatusCode.BadRequest, "unknown scope", grant(scope = "GLOBAL"))
                assertStatus(HttpStatusCode.BadRequest, "TEAM scope without team", grant(scope = "TEAM",
                    capability = "VIEW_TEAMS"))
                assertStatus(HttpStatusCode.BadRequest, "CLUB scope with team",
                    grant(team = "\"$team\""))
                assertStatus(HttpStatusCode.BadRequest, "null teamId",
                    grant(scope = "TEAM", capability = "VIEW_TEAMS", team = "null"))
                assertStatus(HttpStatusCode.BadRequest, "non-UUID teamId",
                    grant(scope = "TEAM", capability = "VIEW_TEAMS", team = "\"nope\""))
                assertStatus(HttpStatusCode.BadRequest, "team-scoped MANAGE_PERMISSIONS",
                    grant(scope = "TEAM", capability = "MANAGE_PERMISSIONS", team = "\"$team\""))
                assertStatus(HttpStatusCode.BadRequest, "team-scoped EDIT_MEMBERS",
                    grant(scope = "TEAM", capability = "EDIT_MEMBERS", team = "\"$team\""))
                assertStatus(HttpStatusCode.BadRequest, "oversized body",
                    grant().dropLast(1) + ",\"x\":\"" + "a".repeat(5000) + "\"}")
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/grants") {
                    header(HttpHeaders.Cookie, "${cookies.sessionName}=manager")
                }.status, "GET without memberId")
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/grants?memberId=nope") {
                    header(HttpHeaders.Cookie, "${cookies.sessionName}=manager")
                }.status, "GET with malformed memberId")

                // Escalation attempts by a valid, correctly signed request: always 403.
                assertStatus(HttpStatusCode.Forbidden, "unknown member",
                    grant(member = UUID.randomUUID().toString()))
                assertStatus(HttpStatusCode.Forbidden, "archived recipient", grant(member = archived.toString()))
                assertStatus(HttpStatusCode.Forbidden, "unknown team",
                    grant(scope = "TEAM", capability = "VIEW_TEAMS", team = "\"${UUID.randomUUID()}\""))
                // Revoking a grant that was never given is an idempotent 204, so only POST can escalate.
                assertStatus(HttpStatusCode.Forbidden, "MANAGE_PERMISSIONS to non-ADMIN",
                    grant(member = plainMember.toString(), capability = "MANAGE_PERMISSIONS"), postOnly = true)
                assertStatus(HttpStatusCode.Forbidden, "MANAGE_PERMISSIONS to ADMIN without login",
                    grant(member = unverifiedAdmin.toString(), capability = "MANAGE_PERMISSIONS"), postOnly = true)
                assertStatus(HttpStatusCode.Forbidden, "self mutation",
                    grant(member = manager.toString(), capability = "EDIT_MEMBERS"))
                // ADMIN role alone, a team-scoped manager and an anonymous cookie never reach the service.
                val ok = grant(member = plainMember.toString())
                assertStatus(HttpStatusCode.Forbidden, "ADMIN without MANAGE_PERMISSIONS grant", ok, "adminnogrant")
                assertStatus(HttpStatusCode.Forbidden, "team MANAGE_TEAMS holder", ok, "coach")
                assertStatus(HttpStatusCode.Unauthorized, "unknown session", ok, "ghost")
                for (handle in listOf("adminnogrant", "coach")) {
                    assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/grants?memberId=$plainMember") {
                        header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
                    }.status, "GET as $handle")
                }

                // The whole matrix, including both verbs, left no grant behind.
                assertEquals(0L, runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchValue("SELECT COUNT(*) FROM club_capability_grants WHERE member_id IN (?, ?, ?, ?, ?)",
                            coach, adminNoGrant, unverifiedAdmin, plainMember, archived)
                    }?.toString()?.toLong()
                })
                assertEquals(1L, runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchValue("SELECT COUNT(*) FROM team_capability_grants WHERE team_id = ?", team)
                    }?.toString()?.toLong()
                })
            }
        } finally {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("DELETE FROM teams WHERE id = ?", team)
                    db.execute("DELETE FROM members WHERE id IN (?, ?, ?, ?, ?, ?)",
                        manager, coach, adminNoGrant, unverifiedAdmin, plainMember, archived)
                    db.execute("DELETE FROM accounts WHERE id IN (?, ?, ?, ?)",
                        managerAccount, coachAccount, adminNoGrantAccount, unverifiedAdminAccount)
                    db.execute("DELETE FROM clubs WHERE slug LIKE 'grant-input-%'")
                }
            }
            database.close()
        }
    }
}
