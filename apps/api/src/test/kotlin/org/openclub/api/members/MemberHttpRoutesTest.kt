package org.openclub.api.members

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class MemberHttpRoutesTest {
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)

    @Test
    fun signedInPermissionedAdminCreatesSearchesEditsAndArchives() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        val club = UUID.randomUUID()
        val account = UUID.randomUUID()
        val otherAccount = UUID.randomUUID()
        val admin = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        var created: UUID? = null
        val revocationTarget = UUID.randomUUID()
        var createdPlayer: UUID? = null
        val sessions = object : BrowserSessionOperations {
            override suspend fun read(handle: String?): ActiveSession? = when (handle) {
                "valid" -> ActiveSession(account, csrf)
                "other" -> ActiveSession(otherAccount, csrf)
                else -> null
            }
            override suspend fun logout(handle: String?, csrfToken: String?): LogoutResult =
                LogoutResult.UNAUTHENTICATED
        }
        val cookie = AuthCookiePolicy.localDevelopment().sessionName
        try {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                        club, "admin-" + club)
                    db.execute("INSERT INTO accounts (id) VALUES (?), (?)", account, otherAccount)
                    db.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name,
                           date_of_birth, email, phone, role) VALUES
                           (?, ?, 'Synthetic', 'Admin', DATE '1990-01-01',
                           'admin@example.invalid', '+41000000000', 'ADMIN'),
                           (?, ?, 'Outsider', 'Person', DATE '1990-01-01',
                           'outsider@example.invalid', '+41000000000', 'MEMBER')""",
                        admin, account, outsider, otherAccount,
                    )
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), account, "member-http-" + account)
                    db.execute(
                        """INSERT INTO club_capability_grants (member_id, capability) VALUES
                           (?, 'VIEW_MEMBER_DIRECTORY'), (?, 'EDIT_MEMBERS')""",
                        admin, admin,
                    )
                }
            }
            testApplication {
                application {
                    module(sessions = sessions, cookiePolicy = AuthCookiePolicy.localDevelopment(),
                        publicOrigin = origin, database = database)
                }
                fun auth(handle: String) = "$cookie=$handle"
                val anonymous = client.get("/api/v1/members")
                assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
                val forbidden = client.get("/api/v1/members") { header(HttpHeaders.Cookie, auth("other")) }
                assertEquals(HttpStatusCode.Forbidden, forbidden.status)
                val missingCsrf = client.post("/api/v1/members") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
                val wrongOrigin = client.post("/api/v1/members") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, "https://attacker.example.invalid")
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.Forbidden, wrongOrigin.status)
                val invalidBody = client.post("/api/v1/members") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"firstName":"Nina","lastName":"Example"}""")
                }
                assertEquals(HttpStatusCode.BadRequest, invalidBody.status)

                // A club may require player gender, while the same field stays optional
                // for non-players and is never returned by the name-only directory.
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("UPDATE clubs SET gender_required_for_players = TRUE WHERE id = ?", club)
                    }
                }
                suspend fun addPlayer(body: String) = client.post("/api/v1/members") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, addPlayer(
                    """{"firstName":"Faye","lastName":"Synthetic","dateOfBirth":"2014-01-01","isPlayer":true}"""
                ).status)
                val playerCreated = addPlayer(
                    """{"firstName":"Faye","lastName":"Synthetic","dateOfBirth":"2014-01-01",
                       "isPlayer":true,"gender":"Synthetic test value"}"""
                )
                assertEquals(HttpStatusCode.Created, playerCreated.status)
                createdPlayer = UUID.fromString(Json.parseToJsonElement(playerCreated.bodyAsText())
                    .jsonObject.getValue("id").jsonPrimitive.content)
                val playerList = client.get("/api/v1/members?q=Faye") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }
                assertEquals(HttpStatusCode.OK, playerList.status)
                assertFalse(playerList.bodyAsText().contains("Synthetic test value"))
                runBlocking {
                    database.onDatabaseThread { db ->
                        assertEquals(true, db.fetchOne("SELECT is_player FROM members WHERE id = ?",
                            createdPlayer)?.get(0, Boolean::class.java))
                        db.execute("UPDATE clubs SET gender_required_for_players = FALSE WHERE id = ?", club)
                    }
                }

                val create = client.post("/api/v1/members") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"firstName":"Nina","lastName":"Private","dateOfBirth":"2013-01-01"}""")
                }
                assertEquals(HttpStatusCode.Created, create.status)
                val createdBody = Json.parseToJsonElement(create.bodyAsText()).jsonObject
                // OpenAPI MemberReference: never echo birth date or adult/minor contacts.
                assertEquals(setOf("id"), createdBody.keys)
                val id = UUID.fromString(createdBody.getValue("id").jsonPrimitive.content)
                created = id
                assertEquals("/api/v1/members/$id", create.headers[HttpHeaders.Location])
                val list = client.get("/api/v1/members?q=Nina") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }
                assertEquals(HttpStatusCode.OK, list.status)
                val memberPage = Json.parseToJsonElement(list.bodyAsText()).jsonObject
                // The unpaginated M1 response must match MemberPage without private fields.
                assertEquals(setOf("items"), memberPage.keys)
                val items = memberPage.getValue("items").jsonArray
                assertEquals(1, items.size)
                assertEquals(setOf("id", "firstName", "lastName"), items.first().jsonObject.keys)
                assertEquals(id.toString(), items.first().jsonObject.getValue("id").jsonPrimitive.content)
                assertFalse(list.bodyAsText().contains("2013-01-01"))
                val privateAttempt = client.get("/api/v1/members/$id") {
                    header(HttpHeaders.Cookie, auth("other"))
                }
                assertEquals(HttpStatusCode.Forbidden, privateAttempt.status)
                val detail = client.get("/api/v1/members/$id") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }
                assertEquals(HttpStatusCode.OK, detail.status)
                val summary = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
                assertEquals(setOf("id", "firstName", "lastName"), summary.keys)
                assertEquals("Private", summary.getValue("lastName").jsonPrimitive.content)

                // The documented optional empty filter is a valid unfiltered read.
                val emptyFilter = client.get("/api/v1/members?q=") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }
                assertEquals(HttpStatusCode.OK, emptyFilter.status)
                // Invalid UUIDs fail input validation; valid-but-unknown IDs stay 404.
                val malformedDetail = client.get("/api/v1/members/not-a-uuid") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }
                assertEquals(HttpStatusCode.BadRequest, malformedDetail.status)
                val invalidResponse = Json.parseToJsonElement(malformedDetail.bodyAsText()).jsonObject
                assertEquals(setOf("code", "message"), invalidResponse.keys)
                assertEquals("invalid", invalidResponse.getValue("code").jsonPrimitive.content)
                assertEquals("Request rejected", invalidResponse.getValue("message").jsonPrimitive.content)
                val missingDetail = client.get("/api/v1/members/${UUID.randomUUID()}") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }
                assertEquals(HttpStatusCode.NotFound, missingDetail.status)

                val edit = client.patch("/api/v1/members/$id") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"firstName":"Nina","lastName":"Renamed"}""")
                }
                assertEquals(HttpStatusCode.NoContent, edit.status)
                val public = client.get("/api/v1/public/members")
                assertEquals(HttpStatusCode.OK, public.status)
                val publicEntries = Json.parseToJsonElement(public.bodyAsText()).jsonArray
                assertTrue(publicEntries.isNotEmpty())
                assertTrue(publicEntries.all { it.jsonObject.keys == setOf("displayName") })
                assertTrue(public.bodyAsText().contains("Nina R."))
                assertFalse(public.bodyAsText().contains("Renamed"))
                assertFalse(public.bodyAsText().contains(id.toString()))

                val archive = client.post("/api/v1/members/$id/archive") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.NoContent, archive.status)
                assertFalse(client.get("/api/v1/public/members").bodyAsText().contains("Nina"))
                val afterArchive = client.get("/api/v1/members/$id") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }
                assertEquals(HttpStatusCode.NotFound, afterArchive.status)
                val repeated = client.post("/api/v1/members/$id/archive") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.NotFound, repeated.status)
                val protectedArchive = client.post("/api/v1/members/$admin/archive") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.Conflict, protectedArchive.status)

                // A live session must not retain a revoked write capability. Revocation
                // must leave the independent directory read grant usable, and
                // restoring the write grant must allow that same session to write again.
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("""DELETE FROM club_capability_grants
                            WHERE member_id = ? AND capability = 'EDIT_MEMBERS'""", admin)
                    }
                }
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/members?q=Synthetic") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }.status)
                val deniedWithLiveSession = client.patch("/api/v1/members/$admin") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"firstName":"ShouldNotChange","lastName":"Admin"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, deniedWithLiveSession.status)
                assertEquals("Synthetic", runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchOne("SELECT first_name FROM members WHERE id = ?", admin)
                            ?.get(0, String::class.java)
                    }
                })
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("""INSERT INTO club_capability_grants (member_id, capability)
                            VALUES (?, 'EDIT_MEMBERS')""", admin)
                    }
                }
                val restoredWithSameSession = client.patch("/api/v1/members/$admin") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"firstName":"Synthetic","lastName":"Admin"}""")
                }
                assertEquals(HttpStatusCode.NoContent, restoredWithSameSession.status)

                // Revoking the directory grant immediately denies subsequent requests.
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("DELETE FROM club_capability_grants WHERE member_id = ? AND capability = 'VIEW_MEMBER_DIRECTORY'",
                            admin)
                    }
                }
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/members") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }.status)
                runBlocking {
                    database.onDatabaseThread { db ->
                        db.execute("""INSERT INTO members (id, first_name, last_name,
                            date_of_birth) VALUES (?, 'Revocation', 'Target', DATE '2014-01-01')""",
                            revocationTarget)
                        db.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", admin)
                        db.execute("DELETE FROM account_identities WHERE account_id = ?", account)
                    }
                }
                val revokedList = client.get("/api/v1/members") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }
                assertEquals(HttpStatusCode.Forbidden, revokedList.status)
                assertFalse(revokedList.bodyAsText().contains("Synthetic"))
                val revokedDetail = client.get("/api/v1/members/$admin") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }
                assertEquals(HttpStatusCode.Forbidden, revokedDetail.status)
                val identityRevokedCreate = client.post("/api/v1/members") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"firstName":"Denied","lastName":"Unverified",
                        "dateOfBirth":"2013-01-01"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, identityRevokedCreate.status)
                val identityRevokedArchive = client.post("/api/v1/members/$revocationTarget/archive") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.Forbidden, identityRevokedArchive.status)
                assertNull(runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchValue("SELECT archived_at FROM members WHERE id = ?", revocationTarget)
                    }
                })

                val identityRevokedEdit = client.patch("/api/v1/members/$admin") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"firstName":"Denied","lastName":"IdentityRevoked"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, identityRevokedEdit.status)
                assertEquals(0, runBlocking {
                    database.onDatabaseThread { db ->
                        db.fetchValue("SELECT COUNT(*) FROM members WHERE first_name = 'Denied'")?.toString()?.toInt()
                    }
                })
            }
        } finally {
            runBlocking {
                database.onDatabaseThread { db ->
                    created?.let { db.execute("DELETE FROM members WHERE id = ?", it) }
                    db.execute("DELETE FROM members WHERE id = ?", revocationTarget)
                    createdPlayer?.let { db.execute("DELETE FROM members WHERE id = ?", it) }
                    db.execute("DELETE FROM members WHERE id IN (?, ?)", admin, outsider)
                    db.execute("DELETE FROM accounts WHERE id IN (?, ?)", account, otherAccount)
                    db.execute("DELETE FROM clubs WHERE id = ?", club)
                }
            }
            database.close()
        }
    }
}
