package org.openclub.api.teams

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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

class TeamMemberHttpRoutesTest {
    @Test
    fun rosterRequiresDualVisibilityAndAssignmentsRequireLiveTeamManagement() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        val club = UUID.randomUUID()
        val account = UUID.randomUUID()
        val manager = UUID.randomUUID()
        val target = UUID.randomUUID()
        val team = UUID.randomUUID()
        val otherTeam = UUID.randomUUID()
        val csrf = "x".repeat(43)
        val origin = "http://localhost:5173"
        val cookies = AuthCookiePolicy.localDevelopment()
        val sessions = object : BrowserSessionOperations {
            override suspend fun read(handle: String?): ActiveSession? =
                if (handle == "manager") ActiveSession(account, csrf) else null
            override suspend fun logout(handle: String?, csrfToken: String?) =
                LogoutResult.UNAUTHENTICATED
        }
        try {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                        club, "team-members-" + club)
                    db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                    db.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name,
                           date_of_birth, email, phone) VALUES
                           (?, ?, 'Manager', 'Synthetic', DATE '1990-01-01',
                            'manager@example.invalid', '+41000000000')""", manager, account)
                    db.execute(
                        """INSERT INTO members (id, first_name, last_name,
                           date_of_birth, email, phone) VALUES
                           (?, 'Target', 'Synthetic', DATE '2000-01-01',
                            'target@example.invalid', '+41000000000')""", target)
                    db.execute("INSERT INTO teams (id, name) VALUES (?, 'Visible'), (?, 'Hidden')",
                        team, otherTeam)
                    db.execute("""INSERT INTO team_capability_grants (member_id, team_id, capability)
                                  VALUES (?, ?, 'VIEW_TEAMS')""", manager, team)
                }
            }
            testApplication {
                application {
                    module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin,
                        database = database)
                }
                fun auth() = "${cookies.sessionName}=manager"
                fun roster(id: UUID) = "/api/v1/teams/$id/members"
                fun assignment(id: UUID) = roster(id) + "/$target"
                assertEquals(HttpStatusCode.Unauthorized, client.get(roster(team)).status)
                assertEquals(HttpStatusCode.Forbidden, client.get(roster(team)) {
                    header(HttpHeaders.Cookie, auth())
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.post(assignment(team)) {
                    header(HttpHeaders.Cookie, auth())
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }.status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')",
                            manager)
                        db.execute("""INSERT INTO team_capability_grants (member_id, team_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')""",
                            manager, team)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden, client.get(roster(otherTeam)) {
                    header(HttpHeaders.Cookie, auth())
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.post(assignment(otherTeam)) {
                    header(HttpHeaders.Cookie, auth())
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.post(assignment(team)) {
                    header(HttpHeaders.Cookie, auth())
                    header(HttpHeaders.Origin, "https://attacker.example.invalid")
                    header("X-CSRF-Token", csrf)
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.post(assignment(team)) {
                    header(HttpHeaders.Cookie, auth())
                    header(HttpHeaders.Origin, origin)
                }.status)
                assertEquals(HttpStatusCode.BadRequest, client.get(roster(team) + "?cursor=garbage") {
                    header(HttpHeaders.Cookie, auth())
                }.status)
                assertEquals(HttpStatusCode.NoContent, client.post(assignment(team)) {
                    header(HttpHeaders.Cookie, auth())
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }.status)
                val listed = client.get(roster(team)) {
                    header(HttpHeaders.Cookie, auth())
                }
                assertEquals(HttpStatusCode.OK, listed.status)
                assertEquals("no-store", listed.headers[HttpHeaders.CacheControl])
                assertTrue(listed.bodyAsText().contains("Target"))
                assertFalse(listed.bodyAsText().contains("manager@example.invalid"))
                assertEquals(HttpStatusCode.Conflict, client.post(assignment(team)) {
                    header(HttpHeaders.Cookie, auth())
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }.status)
                assertEquals(HttpStatusCode.NoContent, client.delete(assignment(team)) {
                    header(HttpHeaders.Cookie, auth())
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }.status)
                assertEquals(HttpStatusCode.NotFound, client.delete(assignment(team)) {
                    header(HttpHeaders.Cookie, auth())
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }.status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("""DELETE FROM team_capability_grants
                                      WHERE member_id = ? AND capability = 'MANAGE_TEAMS'""", manager)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden, client.post(assignment(team)) {
                    header(HttpHeaders.Cookie, auth())
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }.status)
            }
        } finally {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("DELETE FROM teams WHERE id IN (?, ?)", team, otherTeam)
                    db.execute("DELETE FROM members WHERE id IN (?, ?)", manager, target)
                    db.execute("DELETE FROM accounts WHERE id = ?", account)
                    db.execute("DELETE FROM clubs WHERE id = ?", club)
                }
            }
            database.close()
        }
    }
}
