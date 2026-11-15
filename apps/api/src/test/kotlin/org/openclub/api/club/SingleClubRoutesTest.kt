package org.openclub.api.club

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openclub.api.module
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID

class SingleClubRoutesTest {
    @Test
    fun requiresVerifiedSessionAndLinkedLocalMember() {
        val environment = System.getenv()
        assumeTrue(!environment["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(environment))
        val club = UUID.randomUUID()
        val account = UUID.randomUUID()
        val member = UUID.randomUUID()
        val sessions = object : BrowserSessionOperations {
            override suspend fun read(handle: String?): ActiveSession? =
                if (handle == "valid") ActiveSession(account, "x".repeat(43)) else null

            override suspend fun logout(handle: String?, csrfToken: String?): LogoutResult =
                LogoutResult.UNAUTHENTICATED
        }

        try {
            runBlocking {
                database.onDatabaseThread {
                    it.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)",
                        club, "Synthetic club", "club-" + club)
                    it.execute("INSERT INTO accounts (id) VALUES (?)", account)
                }
            }
            testApplication {
                application {
                    module(
                        sessions = sessions,
                        cookiePolicy = AuthCookiePolicy.localDevelopment(),
                        publicOrigin = "http://localhost:5173",
                        database = database,
                    )
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/club").status)
                val denied = client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                }
                assertEquals(HttpStatusCode.Forbidden, denied.status)

                runBlocking {
                    database.onDatabaseThread {
                        it.execute("""INSERT INTO members
                            (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                            VALUES (?, ?, 'Synthetic', 'Admin', DATE '1990-01-01',
                              'sample@example.invalid', '+41000000000', 'ADMIN')""",
                            member, account)
                    }
                }
                val allowed = client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                }
                assertEquals(HttpStatusCode.OK, allowed.status)
                assertTrue(allowed.bodyAsText().contains(club.toString()))
            }
        } finally {
            runBlocking {
                database.onDatabaseThread {
                    it.execute("DELETE FROM members WHERE id = ?", member)
                    it.execute("DELETE FROM accounts WHERE id = ?", account)
                    it.execute("DELETE FROM clubs WHERE id = ?", club)
                }
            }
            database.close()
        }
    }
}
