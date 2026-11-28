package org.openclub.api.members

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
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
import org.openclub.api.checkContract

class MemberRoleHttpRoutesTest {
    @Test
    fun roleAdministrationRequiresActiveVerifiedManagerAndPreservesLastAdmin() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        val club = UUID.randomUUID()
        val account = UUID.randomUUID()
        val admin = UUID.randomUUID()
        val regular = UUID.randomUUID()
        val identity = UUID.randomUUID()
        val cookies = AuthCookiePolicy.localDevelopment()
        val origin = "http://localhost:5173"
        val csrf = "x".repeat(43)
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
                        club, "roles-http-" + club)
                    db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name,
                        date_of_birth, email, phone, role)
                        VALUES (?, ?, 'Admin', 'Synthetic', DATE '1990-01-01',
                        'admin@example.invalid', '+41000000000', 'ADMIN')""", admin, account)
                    db.execute("""INSERT INTO members (id, first_name, last_name,
                        date_of_birth, email, phone)
                        VALUES (?, 'Target', 'Synthetic', DATE '2000-01-01',
                        'target@example.invalid', '+41000000000')""", regular)
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        identity, account, "roles-" + account)
                }
            }
            testApplication {
                application { checkContract();
                    module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = database)
                }
                fun path(id: UUID) = "/api/v1/members/$id/role"
                suspend fun read(id: UUID) = client.get(path(id)) {
                    header(HttpHeaders.Cookie, "${cookies.sessionName}=manager")
                }
                suspend fun update(id: UUID, body: String, withCsrf: Boolean = true,
                    requestOrigin: String = origin) = client.patch(path(id)) {
                    header(HttpHeaders.Cookie, "${cookies.sessionName}=manager")
                    header(HttpHeaders.Origin, requestOrigin)
                    if (withCsrf) header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(body)
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get(path(regular)).status)
                assertEquals(HttpStatusCode.Unauthorized, client.patch(path(regular)) {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"role":"ADMIN"}""")
                }.status)
                assertEquals(HttpStatusCode.Forbidden, read(regular).status)
                assertEquals(HttpStatusCode.Forbidden, update(regular, """{"role":"ADMIN"}""").status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", admin)
                    }
                }
                assertEquals(HttpStatusCode.OK, read(regular).status)
                assertEquals("""{"role":"MEMBER"}""", read(regular).bodyAsText())
                assertEquals(HttpStatusCode.Forbidden, update(regular, """{"role":"ADMIN"}""",
                    withCsrf = false).status)
                assertEquals(HttpStatusCode.Forbidden, update(regular, """{"role":"ADMIN"}""",
                    requestOrigin = "https://invalid.example.invalid").status)
                assertEquals(HttpStatusCode.BadRequest, update(regular, """{"role":"OWNER"}""").status)
                assertEquals(HttpStatusCode.BadRequest, update(regular, """{"role":"ADMIN","accountId":"forged"}""").status)
                assertEquals(HttpStatusCode.NoContent, update(regular, """{"role":"ADMIN"}""").status)
                assertEquals("""{"role":"ADMIN"}""", read(regular).bodyAsText())
                // Role holders (OpenAPI 1.38.0): names and role, both administrators listed, no contacts.
                val holders = client.get("/api/v1/roles/holders") { header(HttpHeaders.Cookie, "${cookies.sessionName}=manager") }
                assertEquals(HttpStatusCode.OK, holders.status)
                assertEquals("no-store", holders.headers[HttpHeaders.CacheControl])
                val holderText = holders.bodyAsText()
                assertTrue(holderText.contains("\"memberId\":\"$admin\"") && holderText.contains("\"memberId\":\"$regular\""))
                assertTrue(holderText.contains("\"role\":\"ADMIN\""))
                assertFalse(holderText.contains("@example.invalid") || holderText.contains("+41"))
                assertEquals(HttpStatusCode.NoContent, update(regular, """{"role":"ADMIN"}""").status)
                assertEquals(HttpStatusCode.Forbidden, update(admin, """{"role":"MEMBER"}""").status)
                assertEquals(HttpStatusCode.NoContent, update(regular, """{"role":"MEMBER"}""").status)
                assertEquals("""{"role":"MEMBER"}""", read(regular).bodyAsText())
                assertFalse(client.get("/api/v1/roles/holders") { header(HttpHeaders.Cookie, "${cookies.sessionName}=manager") }
                    .bodyAsText().contains("\"memberId\":\"$regular\""), "a member is not a role holder")
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("DELETE FROM club_capability_grants WHERE member_id = ?", admin)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/roles/holders") {
                    header(HttpHeaders.Cookie, "${cookies.sessionName}=manager") }.status)
                assertEquals(HttpStatusCode.Forbidden, read(regular).status)
                assertEquals(HttpStatusCode.Forbidden, update(regular, """{"role":"ADMIN"}""").status)
                assertEquals("MEMBER", runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchOne("SELECT role FROM members WHERE id = ?", regular)
                            ?.get(0, String::class.java)
                    }
                })
                // Restoring a role manager's DB grant restores privilege to the
                // same session, but does not bypass verified-identity revocation.
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", admin)
                    }
                }
                val restoredRead = read(regular)
                assertEquals(HttpStatusCode.OK, restoredRead.status)
                assertEquals("""{"role":"MEMBER"}""", restoredRead.bodyAsText())
                assertEquals(HttpStatusCode.NoContent, update(regular, """{"role":"MEMBER"}""").status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("DELETE FROM account_identities WHERE id = ?", identity)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden, read(regular).status)
                assertEquals(HttpStatusCode.Forbidden, update(regular, """{"role":"ADMIN"}""").status)
                assertEquals("MEMBER", runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchOne("SELECT role FROM members WHERE id = ?", regular)
                            ?.get(0, String::class.java)
                    }
                })
            }
        } finally {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("DELETE FROM club_capability_grants WHERE member_id = ?", admin)
                    db.execute("DELETE FROM members WHERE id IN (?, ?)", admin, regular)
                    db.execute("DELETE FROM accounts WHERE id = ?", account)
                    db.execute("DELETE FROM clubs WHERE id = ?", club)
                }
            }
            database.close()
        }
    }
}
