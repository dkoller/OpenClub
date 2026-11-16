package org.openclub.api.club

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
                val unauthenticated = client.get("/api/v1/club")
                assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
                assertEquals("no-store", unauthenticated.headers[HttpHeaders.CacheControl])
                assertEquals("""{"code":"unauthenticated","message":"Authentication required"}""", unauthenticated.bodyAsText())
                val invalidSession = client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=invalid")
                }
                assertEquals(HttpStatusCode.Unauthorized, invalidSession.status)
                assertEquals("no-store", invalidSession.headers[HttpHeaders.CacheControl])
                val denied = client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                }
                assertEquals(HttpStatusCode.Forbidden, denied.status)
                assertEquals("no-store", denied.headers[HttpHeaders.CacheControl])
                assertEquals("""{"code":"forbidden","message":"Club membership required"}""", denied.bodyAsText())

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
                assertEquals("no-store", allowed.headers[HttpHeaders.CacheControl])
                assertEquals("""{"id":"$club","name":"Synthetic club","slug":"club-$club","genderRequiredForPlayers":false}""", allowed.bodyAsText())

                // Ordinary members can see local club metadata; unlinking denies access.
                runBlocking {
                    database.onDatabaseThread {
                        it.execute("UPDATE members SET role = 'MEMBER' WHERE id = ?", member)
                    }
                }
                val ordinary = client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                }
                assertEquals(HttpStatusCode.OK, ordinary.status)
                assertEquals("""{"id":"$club","name":"Synthetic club","slug":"club-$club","genderRequiredForPlayers":false}""", ordinary.bodyAsText())
                runBlocking {
                    database.onDatabaseThread {
                        it.execute("UPDATE members SET account_id = NULL WHERE id = ?", member)
                    }
                }
                val unlinked = client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                }
                assertEquals(HttpStatusCode.Forbidden, unlinked.status)
                assertEquals("no-store", unlinked.headers[HttpHeaders.CacheControl])
                runBlocking {
                    database.onDatabaseThread {
                        it.execute("UPDATE members SET account_id = ? WHERE id = ?", account, member)
                    }
                }
                val relinked = client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                }
                assertEquals(HttpStatusCode.OK, relinked.status)
                assertEquals("no-store", relinked.headers[HttpHeaders.CacheControl])
                assertEquals("""{"id":"$club","name":"Synthetic club","slug":"club-$club","genderRequiredForPlayers":false}""", relinked.bodyAsText())
                runBlocking {
                    database.onDatabaseThread {
                        it.execute("DELETE FROM members WHERE id = ?", member)
                    }
                }
                val deletedMember = client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                }
                assertEquals(HttpStatusCode.Forbidden, deletedMember.status)
                assertEquals("no-store", deletedMember.headers[HttpHeaders.CacheControl])
                assertEquals("""{"code":"forbidden","message":"Club membership required"}""", deletedMember.bodyAsText())
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

    @Test
    fun clubNameMutationRequiresVerifiedAdminLiveGrantOriginAndCsrf() {
        val environment = System.getenv()
        assumeTrue(!environment["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(environment))
        val club = UUID.randomUUID()
        val account = UUID.randomUUID()
        val member = UUID.randomUUID()
        val identity = UUID.randomUUID()
        val player = UUID.randomUUID()
        val origin = "http://localhost:5173"
        val csrf = "x".repeat(43)
        val sessions = object : BrowserSessionOperations {
            override suspend fun read(handle: String?): ActiveSession? =
                if (handle == "valid") ActiveSession(account, csrf) else null
            override suspend fun logout(handle: String?, csrfToken: String?) =
                LogoutResult.UNAUTHENTICATED
        }
        try {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Original', ?)",
                        club, "profile-" + club)
                    db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name,
                        date_of_birth, email, phone, role) VALUES
                        (?, ?, 'Synthetic', 'Administrator', DATE '1990-01-01',
                        'admin@example.invalid', '+41000000000', 'ADMIN')""", member, account)
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                                  VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        identity, account, "club-name-" + account)
                }
            }
            testApplication {
                application {
                    module(sessions = sessions,
                        cookiePolicy = AuthCookiePolicy.localDevelopment(),
                        publicOrigin = origin, database = database)
                }
                suspend fun rename(nameJson: String, withCookie: Boolean = true,
                    withCsrf: Boolean = true, requestOrigin: String = origin) =
                    client.patch("/api/v1/club") {
                        if (withCookie) header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                        if (withCsrf) header("X-CSRF-Token", csrf)
                        header(HttpHeaders.Origin, requestOrigin)
                        header(HttpHeaders.ContentType, "application/json")
                        setBody(nameJson)
                    }
                assertEquals(HttpStatusCode.Unauthorized,
                    rename("""{"name":"Denied"}""", withCookie = false).status)
                assertEquals(HttpStatusCode.Forbidden, rename("""{"name":"Denied"}""").status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'MANAGE_PERMISSIONS')",
                            member)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden,
                    rename("""{"name":"Denied"}""", withCsrf = false).status)
                assertEquals(HttpStatusCode.Forbidden,
                    rename("""{"name":"Denied"}""", requestOrigin = "https://invalid.example.invalid").status)
                assertEquals(HttpStatusCode.BadRequest, rename("""{"name":""}""").status)
                assertEquals(HttpStatusCode.BadRequest,
                    rename("""{"name":"Denied","role":"ADMIN"}""").status)
                assertEquals(HttpStatusCode.NoContent, rename("""{"name":"New Club"}""").status)

                suspend fun setPolicy(body: String, withCookie: Boolean = true,
                    withCsrf: Boolean = true, requestOrigin: String = origin) =
                    client.patch("/api/v1/club/player-gender-policy") {
                        if (withCookie) header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                        if (withCsrf) header("X-CSRF-Token", csrf)
                        header(HttpHeaders.Origin, requestOrigin)
                        header(HttpHeaders.ContentType, "application/json")
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.Unauthorized,
                    setPolicy("""{"genderRequiredForPlayers":true}""", withCookie = false).status)
                assertEquals(HttpStatusCode.Forbidden,
                    setPolicy("""{"genderRequiredForPlayers":true}""", withCsrf = false).status)
                assertEquals(HttpStatusCode.Forbidden,
                    setPolicy("""{"genderRequiredForPlayers":true}""",
                        requestOrigin = "https://invalid.example.invalid").status)
                assertEquals(HttpStatusCode.BadRequest,
                    setPolicy("""{"genderRequiredForPlayers":"true"}""").status)
                assertEquals(HttpStatusCode.BadRequest,
                    setPolicy("""{"genderRequiredForPlayers":true,"actorId":"forged"}""").status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("""INSERT INTO members (id, first_name, last_name,
                            date_of_birth, is_player) VALUES (?, 'Unspecified',
                            'Synthetic', DATE '2014-01-01', TRUE)""", player)
                    }
                }
                assertEquals(HttpStatusCode.Conflict,
                    setPolicy("""{"genderRequiredForPlayers":true}""").status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("UPDATE members SET gender = 'Synthetic value' WHERE id = ?", player)
                    }
                }
                assertEquals(HttpStatusCode.NoContent,
                    setPolicy("""{"genderRequiredForPlayers":true}""").status)
                assertTrue(client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                }.bodyAsText().contains("\"genderRequiredForPlayers\":true"))
                assertEquals(HttpStatusCode.NoContent,
                    setPolicy("""{"genderRequiredForPlayers":false}""").status)

                val allowed = client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                }
                assertEquals(HttpStatusCode.OK, allowed.status)
                org.junit.jupiter.api.Assertions.assertTrue(allowed.bodyAsText().contains("New Club"))
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("UPDATE members SET role = 'MEMBER' WHERE id = ?", member)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden, rename("""{"name":"Denied"}""").status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("UPDATE members SET role = 'ADMIN' WHERE id = ?", member)
                        db.execute("DELETE FROM club_capability_grants WHERE member_id = ?", member)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden, rename("""{"name":"Denied"}""").status)
                assertEquals(HttpStatusCode.Forbidden,
                    setPolicy("""{"genderRequiredForPlayers":true}""").status)
                org.junit.jupiter.api.Assertions.assertTrue(client.get("/api/v1/club") {
                    header(HttpHeaders.Cookie, "openclub_dev_session=valid")
                }.bodyAsText().contains("New Club"))
            }
        } finally {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("DELETE FROM club_capability_grants WHERE member_id = ?", member)
                    db.execute("DELETE FROM members WHERE id IN (?, ?)", member, player)
                    db.execute("DELETE FROM accounts WHERE id = ?", account)
                    db.execute("DELETE FROM clubs WHERE id = ?", club)
                }
            }
            database.close()
        }
    }

}
