package org.openclub.api.members

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

class GrantHttpRoutesTest {
    @Test
    fun verifiedManagerDelegatesAndRevokesWithSessionCsrfAndScopeGuards() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        val club = UUID.randomUUID()
        val account = UUID.randomUUID()
        val outsiderAccount = UUID.randomUUID()
        val manager = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val target = UUID.randomUUID()
        val team = UUID.randomUUID()
        val origin = "http://localhost:5173"
        val csrf = "x".repeat(43)
        val cookies = AuthCookiePolicy.localDevelopment()
        val sessions = object : BrowserSessionOperations {
            override suspend fun read(handle: String?): ActiveSession? = when (handle) {
                "manager" -> ActiveSession(account, csrf)
                "outsider" -> ActiveSession(outsiderAccount, csrf)
                else -> null
            }
            override suspend fun logout(handle: String?, csrfToken: String?) =
                LogoutResult.UNAUTHENTICATED
        }
        try {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                        club, "grant-http-" + club)
                    db.execute("INSERT INTO accounts (id) VALUES (?), (?)", account, outsiderAccount)
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                                  VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), account, UUID.randomUUID().toString())
                    db.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth,
                           email, phone, role) VALUES
                           (?, ?, 'Manager', 'Synthetic', DATE '1990-01-01',
                            'manager@example.invalid', '+41000000000', 'ADMIN'),
                           (?, ?, 'Outsider', 'Synthetic', DATE '1990-01-01',
                            'outsider@example.invalid', '+41000000000', 'MEMBER')""",
                        manager, account, outsider, outsiderAccount)
                    db.execute(
                        """INSERT INTO members (id, first_name, last_name, date_of_birth,
                           email, phone) VALUES (?, 'Target', 'Synthetic', DATE '1990-01-01',
                            'target@example.invalid', '+41000000000')""", target)
                    db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", manager)
                    db.execute("INSERT INTO teams (id, name) VALUES (?, 'Juniors')", team)
                }
            }
            testApplication {
                application {
                    module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin,
                        database = database)
                }
                val managerCookie = "${cookies.sessionName}=manager"
                val outsiderCookie = "${cookies.sessionName}=outsider"
                val clubBody = """{"memberId":"$target","scope":"CLUB","capability":"VIEW_MEMBER_DIRECTORY"}"""
                fun grantBody(cap: String, scope: String = "CLUB", teamId: UUID? = null) =
                    buildJsonObject {
                        put("memberId", target.toString())
                        put("scope", scope)
                        put("capability", cap)
                        if (teamId != null) put("teamId", teamId.toString())
                    }.toString()
                assertEquals(HttpStatusCode.Unauthorized,
                    client.get("/api/v1/grants?memberId=$target").status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/grants?memberId=$target") {
                    header(HttpHeaders.Cookie, outsiderCookie)
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/grants") {
                    header(HttpHeaders.Cookie, managerCookie)
                    header(HttpHeaders.Origin, origin)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(clubBody)
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/grants") {
                    header(HttpHeaders.Cookie, managerCookie)
                    header(HttpHeaders.Origin, "https://attacker.example.invalid")
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(clubBody)
                }.status)
                assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/grants") {
                    header(HttpHeaders.Cookie, managerCookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(grantBody("EDIT_MEMBERS", "TEAM", team))
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/grants") {
                    header(HttpHeaders.Cookie, outsiderCookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(clubBody)
                }.status)
                assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/grants") {
                    header(HttpHeaders.Cookie, managerCookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(clubBody)
                }.status)
                assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/grants") {
                    header(HttpHeaders.Cookie, managerCookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(grantBody("MANAGE_TEAMS", "TEAM", team))
                }.status)
                val listed = client.get("/api/v1/grants?memberId=$target") {
                    header(HttpHeaders.Cookie, managerCookie)
                }
                assertEquals(HttpStatusCode.OK, listed.status)
                assertEquals("no-store", listed.headers[HttpHeaders.CacheControl])
                assertTrue(listed.bodyAsText().contains("VIEW_MEMBER_DIRECTORY"))
                assertTrue(listed.bodyAsText().contains("MANAGE_TEAMS"))
                assertTrue(listed.bodyAsText().contains(team.toString()))
                assertFalse(listed.bodyAsText().contains("target@example.invalid"))
                // The overview: only club-wide MANAGE_PERMISSIONS, names and grants, no contacts.
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/grants/overview").status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/grants/overview") {
                    header(HttpHeaders.Cookie, outsiderCookie)
                }.status)
                val overview = client.get("/api/v1/grants/overview") {
                    header(HttpHeaders.Cookie, managerCookie)
                }
                assertEquals(HttpStatusCode.OK, overview.status)
                assertEquals("no-store", overview.headers[HttpHeaders.CacheControl])
                val overviewText = overview.bodyAsText()
                assertFalse(overviewText.contains("@example.invalid"))
                assertFalse(overviewText.contains("1990-01-01"))
                val people = Json.parseToJsonElement(overviewText).jsonObject["items"]!!.jsonArray
                    .map { it.jsonObject }.associateBy { it["memberId"]!!.jsonPrimitive.content }
                assertFalse(people.containsKey(outsider.toString()), "members without grants are left out")
                val targetRow = requireNotNull(people[target.toString()])
                assertEquals("Target", targetRow["firstName"]!!.jsonPrimitive.content)
                val targetGrants = targetRow["grants"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf("VIEW_MEMBER_DIRECTORY", "MANAGE_TEAMS"),
                    targetGrants.map { it["capability"]!!.jsonPrimitive.content })
                assertEquals("CLUB", targetGrants[0]["scope"]!!.jsonPrimitive.content)
                assertEquals("Juniors", targetGrants[1]["teamName"]!!.jsonPrimitive.content)
                assertEquals(team.toString(), targetGrants[1]["teamId"]!!.jsonPrimitive.content)
                assertTrue(people.containsKey(manager.toString()))
                assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/grants") {
                    header(HttpHeaders.Cookie, managerCookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"memberId":"$manager","scope":"CLUB","capability":"EDIT_MEMBERS"}""")
                }.status)
                assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/grants") {
                    header(HttpHeaders.Cookie, managerCookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(clubBody)
                }.status)
                assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/grants") {
                    header(HttpHeaders.Cookie, managerCookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(grantBody("MANAGE_TEAMS", "TEAM", team))
                }.status)
                assertFalse(client.get("/api/v1/grants?memberId=$target") {
                    header(HttpHeaders.Cookie, managerCookie)
                }.bodyAsText().contains("MANAGE_TEAMS"))
                // The cookie remains valid, but a database-revoked permission
                // manager must immediately lose both read and write access.
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("DELETE FROM club_capability_grants WHERE member_id = ? " +
                            "AND capability = 'MANAGE_PERMISSIONS'", manager)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/grants?memberId=$target") {
                    header(HttpHeaders.Cookie, managerCookie)
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/grants/overview") {
                    header(HttpHeaders.Cookie, managerCookie)
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/grants") {
                    header(HttpHeaders.Cookie, managerCookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(clubBody)
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/grants") {
                    header(HttpHeaders.Cookie, managerCookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(clubBody)
                }.status)
                assertEquals(0, runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchOne("SELECT COUNT(*) FROM club_capability_grants " +
                            "WHERE member_id = ? AND capability = 'VIEW_MEMBER_DIRECTORY'", target)
                            ?.get(0, Int::class.java)
                    }
                })
            }
        } finally {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("DELETE FROM teams WHERE id = ?", team)
                    db.execute("DELETE FROM members WHERE id IN (?, ?, ?)", manager, outsider, target)
                    db.execute("DELETE FROM accounts WHERE id IN (?, ?)", account, outsiderAccount)
                    db.execute("DELETE FROM clubs WHERE id = ?", club)
                }
            }
            database.close()
        }
    }
}
