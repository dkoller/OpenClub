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

                val create = client.post("/api/v1/members") {
                    header(HttpHeaders.Cookie, auth("valid"))
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"firstName":"Nina","lastName":"Private","dateOfBirth":"2013-01-01"}""")
                }
                assertEquals(HttpStatusCode.Created, create.status)
                val id = UUID.fromString(Json.parseToJsonElement(create.bodyAsText())
                    .jsonObject.getValue("id").jsonPrimitive.content)
                created = id
                assertEquals("/api/v1/members/$id", create.headers[HttpHeaders.Location])
                val list = client.get("/api/v1/members?q=Nina") {
                    header(HttpHeaders.Cookie, auth("valid"))
                }
                assertEquals(HttpStatusCode.OK, list.status)
                val items = Json.parseToJsonElement(list.bodyAsText()).jsonObject
                    .getValue("items").jsonArray
                assertEquals(1, items.size)
                assertEquals(id.toString(), items.first().jsonObject.getValue("id").jsonPrimitive.content)
                assertFalse(list.bodyAsText().contains("2013-01-01"))
                val privateAttempt = client.get("/api/v1/members/$id") {
                    header(HttpHeaders.Cookie, auth("other"))
                }
                assertEquals(HttpStatusCode.Forbidden, privateAttempt.status)

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
            }
        } finally {
            runBlocking {
                database.onDatabaseThread { db ->
                    created?.let { db.execute("DELETE FROM members WHERE id = ?", it) }
                    db.execute("DELETE FROM members WHERE id IN (?, ?)", admin, outsider)
                    db.execute("DELETE FROM accounts WHERE id IN (?, ?)", account, otherAccount)
                    db.execute("DELETE FROM clubs WHERE id = ?", club)
                }
            }
            database.close()
        }
    }
}
