package org.openclub.api.members

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
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

/** Member export for editors (OpenAPI 1.7.0): who may export, what the file contains and what it never does. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberExportHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val members = mutableListOf<UUID>()
    private val teams = mutableListOf<UUID>()
    private val handles = listOf("editor", "manager", "directory", "plain")
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, "x".repeat(43)) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }
    private fun count(query: String, vararg args: Any): Int = sql { (it.fetchValue(query, *args) as Number).toInt() }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { tx ->
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Export HTTP Club', ?)", club, "export-http-$club")
            for (handle in handles) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "export-subject-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    members += it
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                           VALUES (?, ?, ?, 'Actor', DATE '1980-01-01', 'a@example.invalid', '+41000000000', ?)""",
                        it, account, handle, if (handle == "editor") "ADMIN" else "MEMBER")
                }
            }
            tx.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", memberOf.getValue("editor"))
            // A contact reader and a directory reader are still not exporters.
            tx.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_TEAMS')", memberOf.getValue("manager"))
            tx.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", memberOf.getValue("directory"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", members.toTypedArray())
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", members.toTypedArray())
                tx.execute("DELETE FROM teams WHERE id = ANY(?::uuid[])", teams.toTypedArray())
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = "http://localhost:5173", database = requireNotNull(database)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.export(id: Any, handle: String?, site: String? = null): HttpResponse =
        client.get("/api/v1/members/$id/export") {
            if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            if (site != null) header("Sec-Fetch-Site", site)
        }

    private fun exports(target: UUID) = count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_EXPORTED' AND target_member_id = ?", target)

    @Test
    fun onlyEditorsMayExportAndAuthorizationComesBeforeValidation() = app {
        val target = memberOf.getValue("plain")
        assertEquals(HttpStatusCode.Unauthorized, export(target, null).status)
        assertEquals(HttpStatusCode.Unauthorized, export(target, "ghost").status)
        for (handle in listOf("plain", "directory", "manager")) {
            for (id in listOf(target, UUID.randomUUID(), "not-a-uuid")) {
                val refused = export(id, handle)
                assertEquals(HttpStatusCode.Forbidden, refused.status, "$handle $id")
                assertEquals("no-store", refused.headers[HttpHeaders.CacheControl])
                assertNull(refused.headers[HttpHeaders.ContentDisposition])
            }
        }
        assertEquals(HttpStatusCode.BadRequest, export("not-a-uuid", "editor").status)
        assertEquals(HttpStatusCode.NotFound, export(UUID.randomUUID(), "editor").status)
        assertEquals(0, exports(target), "no refusal is audited as an export")
    }

    @Test
    fun anotherSiteCannotMakeAnEditorsBrowserExportOrReadDetails() = app {
        val target = UUID.randomUUID().also { members += it }
        sql { it.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Site', 'Check', DATE '2014-04-04')", target) }
        for (site in listOf("cross-site", "same-site")) {
            val refused = export(target, "editor", site)
            assertEquals(HttpStatusCode.Forbidden, refused.status, site)
            assertNull(refused.headers[HttpHeaders.ContentDisposition], site)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/members/$target/details") {
                header(HttpHeaders.Cookie, "${cookies.sessionName}=editor"); header("Sec-Fetch-Site", site)
            }.status, "details $site")
        }
        assertEquals(0, exports(target), "a refused cross-site request is not audited")
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'CONTACT_READ' AND target_member_id = ?", target))
        // The app itself (same origin) and a typed or bookmarked address still work.
        for (site in listOf("same-origin", "none")) assertEquals(HttpStatusCode.OK, export(target, "editor", site).status, site)
        assertEquals(2, exports(target))
    }

    @Test
    fun anEditorDownloadsTheCompleteExportWithoutLoginDetailsAndEveryExportIsAudited() = app {
        // A minor in a team with a team grant and a guardian: every kind of stored relation appears.
        val minor = UUID.randomUUID().also { members += it }
        val team = UUID.randomUUID().also { teams += it }
        sql {
            it.execute("INSERT INTO members (id, first_name, last_name, date_of_birth, phone, is_player) VALUES (?, 'Kid', 'Export', DATE '2015-02-02', '+41000000055', TRUE)", minor)
            it.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Export team ${team.toString().take(8)}")
            it.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, minor)
            it.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'VIEW_TEAMS')", team, minor)
            it.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", minor, memberOf.getValue("plain"))
        }
        val response = export(minor, "editor")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("attachment; filename=\"member-$minor.json\"", response.headers[HttpHeaders.ContentDisposition])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("exportedAt", "member", "teams", "invitations", "guardianships", "fees", "attendance", "clubGrants", "teamGrants"), body.keys)
        val member = body["member"]!!.jsonObject
        assertEquals("2015-02-02", member["dateOfBirth"]!!.jsonPrimitive.content)
        assertEquals("+41000000055", member["phone"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, member["email"])
        assertFalse(member["hasLinkedLogin"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("Export team ${team.toString().take(8)}"), body["teams"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("VIEW_TEAMS", body["teamGrants"]!!.jsonArray.single().jsonObject["capability"]!!.jsonPrimitive.content)
        val guardianship = body["guardianships"]!!.jsonArray.single().jsonObject
        assertEquals("guardedBy", guardianship["relation"]!!.jsonPrimitive.content)
        assertEquals(memberOf.getValue("plain").toString(), guardianship["memberId"]!!.jsonPrimitive.content)
        assertFalse(response.bodyAsText().contains("plain"), "the guardian's name is not part of the minor's export")
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_EXPORTED' AND target_member_id = ? AND actor_account_id = ?",
            minor, accounts.getValue("editor")))

        // A linked member: the login is a yes, never the account, issuer or subject.
        val linked = export(memberOf.getValue("plain"), "editor").bodyAsText()
        val linkedBody = Json.parseToJsonElement(linked).jsonObject
        assertTrue(linkedBody["member"]!!.jsonObject["hasLinkedLogin"]!!.jsonPrimitive.boolean)
        assertEquals("guardianOf", linkedBody["guardianships"]!!.jsonArray.single().jsonObject["relation"]!!.jsonPrimitive.content)
        for (secret in listOf(accounts.getValue("plain").toString(), "export-subject", "issuer.example.invalid")) {
            assertFalse(linked.contains(secret), "no login binding: $secret")
        }

        // Archived members can be exported too (for example before erasure), and each export is audited.
        val archived = UUID.randomUUID().also { members += it }
        sql { it.execute("INSERT INTO members (id, first_name, last_name, date_of_birth, archived_at) VALUES (?, 'Old', 'Export', DATE '2014-01-01', now())", archived) }
        assertEquals(HttpStatusCode.OK, export(archived, "editor").status)
        assertEquals(HttpStatusCode.OK, export(archived, "editor").status)
        assertEquals(2, exports(archived))
    }
}
