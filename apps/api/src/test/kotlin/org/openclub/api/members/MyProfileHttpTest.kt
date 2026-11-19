package org.openclub.api.members

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.module
import java.util.UUID

/** My profile (OpenAPI 1.9.0): a member reads their own record and changes only their own email and phone. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyProfileHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val team = UUID.randomUUID()
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }
    private fun count(query: String, vararg args: Any): Int = sql { (it.fetchValue(query, *args) as Number).toInt() }
    private fun version(handle: String): String =
        "\"" + (sql { it.fetchValue("SELECT row_version FROM members WHERE id = ?", memberOf.getValue(handle)) } as Number) + "\""

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { tx ->
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Profile HTTP Club', ?)", club, "profile-http-$club")
            // "me" and "other" are verified members; "stranger" has a verified login but no member record;
            // "unverified" is a member whose login lost its identity.
            for (handle in listOf("me", "other", "stranger", "unverified")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                if (handle != "unverified") tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "profile-$account")
                if (handle == "stranger") continue
                memberOf[handle] = UUID.randomUUID().also {
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, gender, is_player)
                           VALUES (?, ?, ?, 'Profile', DATE '1985-05-06', ?, '+41000000010', 'diverse', TRUE)""",
                        it, account, handle, "$handle@example.invalid")
                }
            }
            tx.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Profile test ${team.toString().take(8)}")
            tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, memberOf.getValue("me"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", memberOf.values.toTypedArray())
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", memberOf.values.toTypedArray())
                tx.execute("DELETE FROM teams WHERE id = ?", team)
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.read(handle: String?): HttpResponse = client.request("/api/v1/me/profile") {
        method = HttpMethod.Get
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
    }

    private suspend fun ApplicationTestBuilder.change(
        handle: String?, body: String, ifMatch: String? = null, withOrigin: String? = origin, withCsrf: String? = csrf,
    ): HttpResponse = client.request("/api/v1/me/profile/contacts") {
        method = HttpMethod.Put
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        if (withOrigin != null) header(HttpHeaders.Origin, withOrigin)
        if (withCsrf != null) header("X-CSRF-Token", withCsrf)
        if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
        header(HttpHeaders.ContentType, "application/json"); setBody(body)
    }

    private class Check { val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
        }
        fun done() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    private val contacts = """{"email":"me.new@example.invalid","phone":"+41000000011"}"""

    @Test
    fun aMemberReadsExactlyTheirOwnRecord() = app {
        val response = read("me")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(version("me"), response.headers[HttpHeaders.ETag])
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("id", "firstName", "lastName", "dateOfBirth", "email", "phone", "gender", "isPlayer", "role", "teams"), body.keys)
        assertEquals(memberOf.getValue("me").toString(), body.getValue("id").jsonPrimitive.content)
        assertEquals("1985-05-06", body.getValue("dateOfBirth").jsonPrimitive.content)
        assertEquals("diverse", body.getValue("gender").jsonPrimitive.content)
        assertEquals("MEMBER", body.getValue("role").jsonPrimitive.content)
        val teams = body.getValue("teams").jsonArray.map { it.jsonObject }
        assertEquals(listOf(team.toString()), teams.map { it.getValue("id").jsonPrimitive.content })
        // Reading one's own record is not audited.
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE target_member_id = ?", memberOf.getValue("me")))

        val check = Check()
        check.expect("no session", read(null), HttpStatusCode.Unauthorized)
        check.expect("login without member record", read("stranger"), HttpStatusCode.Forbidden)
        check.expect("member without verified identity", read("unverified"), HttpStatusCode.Forbidden)
        check.done()
    }

    @Test
    fun aMemberChangesOnlyTheirOwnContactsAtTheVersionTheyRead() = app {
        val me = memberOf.getValue("me")
        val read = read("me").headers[HttpHeaders.ETag]!!
        val check = Check()
        // Session, then Origin and CSRF, then membership: all before the body is looked at.
        check.expect("no session", change(null, contacts, read), HttpStatusCode.Unauthorized)
        check.expect("wrong origin", change("me", contacts, read, withOrigin = "https://evil.example.invalid"), HttpStatusCode.Forbidden)
        check.expect("no CSRF", change("me", contacts, read, withCsrf = null), HttpStatusCode.Forbidden)
        check.expect("no member record", change("stranger", "[]"), HttpStatusCode.Forbidden)
        check.expect("unverified member", change("unverified", "[]"), HttpStatusCode.Forbidden)
        // Then the body: only email and phone, in valid form.
        check.expect("other field", change("me", """{"firstName":"Changed"}""", read), HttpStatusCode.BadRequest)
        check.expect("role", change("me", """{"email":"a@example.invalid","role":"ADMIN"}""", read), HttpStatusCode.BadRequest)
        check.expect("bad email", change("me", """{"email":"not-an-email","phone":"+41000000011"}""", read), HttpStatusCode.BadRequest)
        // Then the version, then the club's rules (an adult keeps both email and phone).
        check.expect("no If-Match", change("me", contacts), HttpStatusCode.PreconditionRequired)
        check.expect("malformed If-Match", change("me", contacts, "*"), HttpStatusCode.PreconditionFailed)
        check.expect("other version", change("me", contacts, "\"999\""), HttpStatusCode.PreconditionFailed)
        check.expect("adult without phone", change("me", """{"email":"me@example.invalid"}""", read), HttpStatusCode.BadRequest)
        check.done()
        assertEquals("me@example.invalid", sql { it.fetchValue("SELECT email FROM members WHERE id = ?", me) })
        assertEquals(read, version("me"), "no refusal changed the record")
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'SELF_CONTACTS_UPDATED' AND target_member_id = ?", me))

        val saved = change("me", contacts, read)
        assertEquals(HttpStatusCode.NoContent, saved.status)
        assertEquals(version("me"), saved.headers[HttpHeaders.ETag])
        val row = sql { it.fetchOne("SELECT first_name, email, phone, gender, is_player, role FROM members WHERE id = ?", me)!! }
        assertEquals(listOf("me", "me.new@example.invalid", "+41000000011", "diverse", true, "MEMBER"), row.intoList())
        assertEquals(1, count("""SELECT COUNT(*) FROM audit_events WHERE event_type = 'SELF_CONTACTS_UPDATED'
                                 AND actor_account_id = ? AND target_member_id = ?""", accounts.getValue("me"), me))
        assertEquals(memberOf.getValue("other").let { sql { db -> db.fetchValue("SELECT email FROM members WHERE id = ?", it) } },
            "other@example.invalid", "nobody else's record changes")

        // The version read before the change is now stale, and so is the new one after an editor's change.
        assertEquals(HttpStatusCode.PreconditionFailed, change("me", contacts, read).status)
        val next = saved.headers[HttpHeaders.ETag]!!
        sql { it.execute("UPDATE members SET last_name = 'Edited' WHERE id = ?", me) }
        assertEquals(HttpStatusCode.PreconditionFailed, change("me", contacts, next).status)
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'SELF_CONTACTS_UPDATED' AND target_member_id = ?", me))
    }
}
