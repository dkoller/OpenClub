package org.openclub.api.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openclub.api.module
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID

class SessionIdentityHttpTest {
    @Test
    fun removingVerifiedIdentityImmediatelyDeniesExistingCookieAndStillAllowsLogout() {
        val environment = System.getenv()
        assumeTrue(!environment["DB_URL"].isNullOrBlank(), "Requires synthetic PostgreSQL")
        val db = requireNotNull(DatabaseRuntime.openIfConfigured(environment))
        val account = UUID.randomUUID()
        val origin = "http://localhost:5173"
        val cookies = AuthCookiePolicy.localDevelopment()
        try {
            val issued = runBlocking {
                db.onDatabaseThread { sql ->
                    sql.execute("INSERT INTO accounts (id) VALUES (?)", account)
                    sql.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), account, "http-session-" + account)
                    AuthSessions(sql).create(account)
                }
            }
            testApplication {
                application {
                    module(
                        sessions = PostgresBrowserSessions(db),
                        cookiePolicy = cookies,
                        publicOrigin = origin,
                        database = db,
                    )
                }
                val cookie = "${cookies.sessionName}=${issued.handle}"
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/me") {
                    header(HttpHeaders.Cookie, cookie)
                }.status)
                runBlocking {
                    db.onDatabaseThread { sql ->
                        sql.execute("DELETE FROM account_identities WHERE account_id = ?", account)
                    }
                }
                val denied = client.get("/api/v1/auth/me") {
                    header(HttpHeaders.Cookie, cookie)
                }
                assertEquals(HttpStatusCode.Unauthorized, denied.status)
                assertEquals("no-store", denied.headers[HttpHeaders.CacheControl])
                val logout = client.post("/api/v1/auth/logout") {
                    header(HttpHeaders.Cookie, cookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", issued.csrfToken)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.NoContent, logout.status)
                assertTrue(logout.headers[HttpHeaders.SetCookie]!!.contains("Max-Age=0"))
                assertEquals(0L, runBlocking {
                    db.onDatabaseThread { sql ->
                        sql.fetchValue("SELECT COUNT(*) FROM auth_sessions WHERE account_id = ?", account)
                    }
                })
            }
        } finally {
            runBlocking { db.onDatabaseThread { it.execute("DELETE FROM accounts WHERE id = ?", account) } }
            db.close()
        }
    }
}
