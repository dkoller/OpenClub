package org.openclub.api.members

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
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
import java.time.Instant
import java.util.UUID

/** Archived-member search (OpenAPI 1.5.0): who may read it, what it returns, paging and cursor scoping. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchivedMemberSearchHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val members = mutableListOf<UUID>()
    private val handles = listOf("editor", "directory", "plain")
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, "x".repeat(43)) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }
    // A unique surname keeps this class's rows apart from anything else in the database.
    private val tag = "Arch" + UUID.randomUUID().toString().take(8)

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { tx ->
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Archived HTTP Club', ?)", club, "archived-http-$club")
            for (handle in handles) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "archived-$account")
                val member = UUID.randomUUID().also { members += it }
                tx.execute(
                    """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                       VALUES (?, ?, 'Http', 'Actor', DATE '1985-01-01', 'a@example.invalid', '+41000000000', ?)""",
                    member, account, if (handle == "editor") "ADMIN" else "MEMBER")
                when (handle) {
                    // The editor deliberately has no directory grant: the archived list needs EDIT_MEMBERS only.
                    "editor" -> tx.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", member)
                    "directory" -> tx.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", member)
                }
            }
            // Five archived and two active members sharing the tag; minors so no contacts are needed.
            for (i in 1..5) insert(tx, "Archived$i", archived = true)
            for (i in 1..2) insert(tx, "Active$i", archived = false)
        }
    }

    private fun insert(tx: DSLContext, first: String, archived: Boolean) = UUID.randomUUID().also { id ->
        members += id
        tx.execute(
            """INSERT INTO members (id, first_name, last_name, date_of_birth, archived_at)
               VALUES (?, ?, ?, DATE '2014-01-01', CASE WHEN ? THEN now() END)""", id, first, tag, archived)
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", members.toTypedArray())
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

    private suspend fun ApplicationTestBuilder.get(path: String, handle: String?): HttpResponse = client.get(path) {
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun archived(query: String = tag, extra: String = "") = "/api/v1/members/archived?q=${query.encodeURLParameter()}$extra"

    @Test
    fun onlyEditorsMayReadItAndAuthorizationComesBeforeValidation() = app {
        assertEquals(HttpStatusCode.Unauthorized, get(archived(), null).status)
        assertEquals(HttpStatusCode.Unauthorized, get(archived(), "ghost").status)
        // A directory reader and a plain member get 403 for any input, never 400.
        for (handle in listOf("directory", "plain")) {
            for (path in listOf(archived(), archived(extra = "&limit=0"), archived(extra = "&cursor=junk"), archived("a".repeat(101)))) {
                val response = get(path, handle)
                assertEquals(HttpStatusCode.Forbidden, response.status, "$handle $path")
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                assertFalse(response.bodyAsText().contains(tag), "no names leak")
            }
        }
        for (path in listOf(archived(extra = "&limit=0"), archived(extra = "&limit=51"), archived(extra = "&limit=x"),
            archived(extra = "&cursor=junk"), archived("a".repeat(101)), archived("   "))) {
            assertEquals(HttpStatusCode.BadRequest, get(path, "editor").status, path)
        }
        // The archived list does not replace the directory: the editor without VIEW_MEMBER_DIRECTORY still gets 403 there.
        assertEquals(HttpStatusCode.Forbidden, get("/api/v1/members?q=$tag", "editor").status)
    }

    @Test
    fun itListsOnlyArchivedMembersWithNamesAndArchiveTimeAndPagesThroughThem() = app {
        val first = get(archived(extra = "&limit=2"), "editor")
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("no-store", first.headers[HttpHeaders.CacheControl])
        val page = first.json()
        val items = page["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, items.size)
        for (item in items) {
            assertEquals(setOf("id", "firstName", "lastName", "archivedAt"), item.keys, "names and archive time only")
            val at = item["archivedAt"]!!.jsonPrimitive.content
            assertTrue(at.endsWith("Z"), at)
            assertTrue(Instant.parse(at).isBefore(Instant.now().plusSeconds(60)))
        }
        // Follow the cursor to the end: exactly the five archived members, none of the active ones, no duplicates.
        val names = items.map { it["firstName"]!!.jsonPrimitive.content }.toMutableList()
        var cursor = page["nextCursor"]?.jsonPrimitive?.content
        while (cursor != null) {
            val next = get(archived(extra = "&limit=2&cursor=$cursor"), "editor").json()
            names += next["items"]!!.jsonArray.map { it.jsonObject["firstName"]!!.jsonPrimitive.content }
            cursor = next["nextCursor"]?.jsonPrimitive?.content
        }
        assertEquals((1..5).map { "Archived$it" }.sorted(), names.sorted())

        // Name search narrows the list.
        val one = get(archived("Archived3 $tag"), "editor").json()["items"]!!.jsonArray
        assertEquals(listOf("Archived3"), one.map { it.jsonObject["firstName"]!!.jsonPrimitive.content })
    }

    @Test
    fun cursorsAreBoundToTheirListAndQuery() = app {
        // An active-directory cursor (from a caller holding both grants) is rejected by the archived list.
        val both = accounts.getValue("editor")
        sql { it.execute("INSERT INTO club_capability_grants SELECT id, 'VIEW_MEMBER_DIRECTORY' FROM members WHERE account_id = ?", both) }
        try {
            sql { tx -> for (i in 3..5) insert(tx, "Active$i", archived = false) }
            val directoryCursor = get("/api/v1/members?q=$tag&limit=1", "editor").json()["nextCursor"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.BadRequest, get(archived(extra = "&limit=1&cursor=$directoryCursor"), "editor").status)
            val archivedCursor = get(archived(extra = "&limit=1"), "editor").json()["nextCursor"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.BadRequest, get("/api/v1/members?q=$tag&limit=1&cursor=$archivedCursor", "editor").status)
            assertEquals(HttpStatusCode.BadRequest, get(archived("Other", "&limit=1&cursor=$archivedCursor"), "editor").status,
                "a cursor is bound to its query")
        } finally {
            sql { it.execute("DELETE FROM club_capability_grants g USING members m WHERE g.member_id = m.id AND m.account_id = ? " +
                "AND g.capability = 'VIEW_MEMBER_DIRECTORY'", both) }
        }
    }

    @Test
    fun aReactivatedMemberLeavesTheArchivedList() = app {
        val target = sql { tx -> insert(tx, "Returning", archived = true) }
        assertEquals(1, get(archived("Returning $tag"), "editor").json()["items"]!!.jsonArray.size)
        val reactivated = client.post("/api/v1/members/$target/reactivate") {
            header(HttpHeaders.Cookie, "${cookies.sessionName}=editor")
            header(HttpHeaders.Origin, origin)
            header("X-CSRF-Token", "x".repeat(43))
        }
        assertEquals(HttpStatusCode.NoContent, reactivated.status)
        assertEquals(0, get(archived("Returning $tag"), "editor").json()["items"]!!.jsonArray.size)
    }
}
