package org.openclub.api.teams

import io.ktor.client.request.get
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
                    db.execute("INSERT INTO teams (id, name) VALUES (?, 'Scoped Team'), (?, 'Hidden Team')",
                        visible, secret)
                    db.execute("""INSERT INTO team_capability_grants (member_id, team_id, capability)
                                  VALUES (?, ?, 'VIEW_TEAMS')""", viewer, visible)
                }
            }
            testApplication {
                application {
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
