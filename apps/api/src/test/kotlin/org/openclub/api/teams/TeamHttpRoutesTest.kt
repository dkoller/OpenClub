package org.openclub.api.teams

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import org.openclub.api.checkContract

class TeamHttpRoutesTest {
    @Test
    fun teamDirectoryRequiresVerifiedGrantAndLimitsScopedVisibility() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        val club = UUID.randomUUID()
        val viewerAccount = UUID.randomUUID()
        val outsiderAccount = UUID.randomUUID()
        val viewer = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val visible = UUID.randomUUID()
        val secret = UUID.randomUUID()
        var created: UUID? = null
        val cookiePolicy = AuthCookiePolicy.localDevelopment()
        val sessions = object : BrowserSessionOperations {
            override suspend fun read(handle: String?): ActiveSession? = when (handle) {
                "viewer" -> ActiveSession(viewerAccount, "x".repeat(43))
                "outsider" -> ActiveSession(outsiderAccount, "x".repeat(43))
                else -> null
            }
            override suspend fun logout(handle: String?, csrfToken: String?) =
                LogoutResult.UNAUTHENTICATED
        }
        try {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                        club, "teams-http-" + club)
                    db.execute("INSERT INTO accounts (id) VALUES (?), (?)", viewerAccount, outsiderAccount)
                    db.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name,
                           date_of_birth, email, phone) VALUES
                           (?, ?, 'Viewer', 'Synthetic', DATE '1990-01-01',
                             'viewer@example.invalid', '+41000000000'),
                           (?, ?, 'Outsider', 'Synthetic', DATE '1990-01-01',
                             'outsider@example.invalid', '+41000000000')""",
                        viewer, viewerAccount, outsider, outsiderAccount,
                    )
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), viewerAccount, "teams-http-" + viewerAccount)
                    db.execute("INSERT INTO teams (id, name) VALUES (?, 'Scoped Team'), (?, 'Hidden Team')",
                        visible, secret)
                    db.execute("""INSERT INTO team_capability_grants (member_id, team_id, capability)
                                  VALUES (?, ?, 'VIEW_TEAMS')""", viewer, visible)
                }
            }
            testApplication {
                application { checkContract();
                    module(sessions = sessions, cookiePolicy = cookiePolicy,
                        publicOrigin = "http://localhost:5173", database = database)
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/teams").status)
                val denied = client.get("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=outsider")
                }
                assertEquals(HttpStatusCode.Forbidden, denied.status)
                assertFalse(denied.bodyAsText().contains("Scoped Team"))
                val allowed = client.get("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                }
                assertEquals(HttpStatusCode.OK, allowed.status)
                assertEquals("no-store", allowed.headers[HttpHeaders.CacheControl])
                val items = Json.parseToJsonElement(allowed.bodyAsText()).jsonObject
                    .getValue("items").jsonArray
                assertEquals(1, items.size)
                assertTrue(allowed.bodyAsText().contains("Scoped Team"))
                assertFalse(allowed.bodyAsText().contains("Hidden Team"))
                val badCsrf = client.post("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                    header(HttpHeaders.Origin, "http://localhost:5173")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Forbidden"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, badCsrf.status)
                val wrongOrigin = client.post("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                    header(HttpHeaders.Origin, "https://attacker.example.invalid")
                    header("X-CSRF-Token", "x".repeat(43))
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Forbidden"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, wrongOrigin.status)
                val teamScopedCannotCreate = client.post("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                    header(HttpHeaders.Origin, "http://localhost:5173")
                    header("X-CSRF-Token", "x".repeat(43))
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Forbidden"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, teamScopedCannotCreate.status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_TEAMS')", viewer)
                    }
                }
                val invalid = client.post("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                    header(HttpHeaders.Origin, "http://localhost:5173")
                    header("X-CSRF-Token", "x".repeat(43))
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Test","actorAccountId":"injected"}""")
                }
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                val create = client.post("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                    header(HttpHeaders.Origin, "http://localhost:5173")
                    header("X-CSRF-Token", "x".repeat(43))
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"New Team"}""")
                }
                assertEquals(HttpStatusCode.Created, create.status)
                created = UUID.fromString(Json.parseToJsonElement(create.bodyAsText())
                    .jsonObject.getValue("id").jsonPrimitive.content)
                assertEquals("/api/v1/teams/$created", create.headers[HttpHeaders.Location])
                assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                    header(HttpHeaders.Origin, "http://localhost:5173")
                    header("X-CSRF-Token", "x".repeat(43))
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"new team"}""")
                }.status)
                suspend fun rename(handle: String, teamId: UUID, newName: String, sendCsrf: Boolean = true,
                    requestOrigin: String = "http://localhost:5173") =
                    client.patch("/api/v1/teams/$teamId") {
                        header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=$handle")
                        header(HttpHeaders.Origin, requestOrigin)
                        if (sendCsrf) header("X-CSRF-Token", "x".repeat(43))
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("""{"name":"$newName"}""")
                    }
                assertEquals(HttpStatusCode.Unauthorized, client.patch("/api/v1/teams/$visible") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Denied"}""")
                }.status)
                assertEquals(HttpStatusCode.Forbidden, rename("outsider", visible, "Denied").status)
                assertEquals(HttpStatusCode.Forbidden, rename("viewer", visible, "Denied", false).status)
                assertEquals(HttpStatusCode.Forbidden,
                    rename("viewer", visible, "Denied", requestOrigin = "https://attacker.example.invalid").status)
                assertEquals(HttpStatusCode.BadRequest, client.patch("/api/v1/teams/$visible") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                    header(HttpHeaders.Origin, "http://localhost:5173")
                    header("X-CSRF-Token", "x".repeat(43))
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Denied","accountId":"injected"}""")
                }.status)
                assertEquals(HttpStatusCode.Conflict, rename("viewer", visible, "hidden team").status)
                assertEquals(HttpStatusCode.NotFound,
                    rename("viewer", UUID.randomUUID(), "Missing").status)
                assertEquals(HttpStatusCode.NoContent, rename("viewer", visible, "Renamed Team").status)
                assertTrue(client.get("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                }.bodyAsText().contains("Renamed Team"))
                assertEquals("Renamed Team", runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchOne("SELECT name FROM teams WHERE id = ?", visible)?.get(0, String::class.java)
                    }
                })
                // A session with a stale, identity-revoked account must reveal no team names.
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("DELETE FROM account_identities WHERE account_id = ?", viewerAccount)
                    }
                }
                val revokedRead = client.get("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                }
                assertEquals(HttpStatusCode.Forbidden, revokedRead.status)
                assertFalse(revokedRead.bodyAsText().contains("Renamed Team"))
                assertEquals(HttpStatusCode.Forbidden, rename("viewer", visible, "Revoked").status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                            VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                            UUID.randomUUID(), viewerAccount, "teams-restored-" + viewerAccount)
                    }
                }
                // Deletion removes the team and its assignments, never a member.
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", created, outsider)
                    }
                }
                suspend fun deleteTeam(handle: String, teamId: UUID, expectedName: String,
                    requestOrigin: String = "http://localhost:5173", sendCsrf: Boolean = true) =
                    client.delete("/api/v1/teams/$teamId") {
                        header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=$handle")
                        header(HttpHeaders.Origin, requestOrigin)
                        if (sendCsrf) header("X-CSRF-Token", "x".repeat(43))
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("""{"name":"$expectedName"}""")
                    }
                assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/teams/$created").status)
                assertEquals(HttpStatusCode.Forbidden, deleteTeam("outsider", created!!, "New Team").status)
                assertEquals(HttpStatusCode.Forbidden,
                    deleteTeam("viewer", created!!, "New Team", sendCsrf = false).status)
                assertEquals(HttpStatusCode.Forbidden,
                    deleteTeam("viewer", created!!, "New Team",
                        requestOrigin = "https://attacker.example.invalid").status)
                assertEquals(HttpStatusCode.BadRequest, deleteTeam("viewer", created!!, "Old Team").status)
                assertEquals(HttpStatusCode.NotFound,
                    deleteTeam("viewer", UUID.randomUUID(), "Missing").status)
                assertEquals(HttpStatusCode.NoContent, deleteTeam("viewer", created!!, "New Team").status)
                assertEquals(0, runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchValue("SELECT COUNT(*) FROM member_team_assignments WHERE member_id = ?", outsider)
                            ?.toString()?.toInt()
                    }
                })
                assertEquals(1, runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchValue("SELECT COUNT(*) FROM members WHERE id = ?", outsider)
                            ?.toString()?.toInt()
                    }
                })
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("DELETE FROM club_capability_grants WHERE member_id = ? AND capability = 'MANAGE_TEAMS'",
                            viewer)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden, rename("viewer", visible, "Denied").status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("DELETE FROM team_capability_grants WHERE member_id = ?", viewer)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/teams") {
                    header(HttpHeaders.Cookie, "${cookiePolicy.sessionName}=viewer")
                }.status)
            }
        } finally {
            runBlocking {
                database.onDatabaseThread { db ->
                    created?.let { db.execute("DELETE FROM teams WHERE id = ?", it) }
                    db.execute("DELETE FROM teams WHERE id IN (?, ?)", visible, secret)
                    db.execute("DELETE FROM members WHERE id IN (?, ?)", viewer, outsider)
                    db.execute("DELETE FROM accounts WHERE id IN (?, ?)", viewerAccount, outsiderAccount)
                    db.execute("DELETE FROM clubs WHERE id = ?", club)
                }
            }
            database.close()
        }
    }
}
