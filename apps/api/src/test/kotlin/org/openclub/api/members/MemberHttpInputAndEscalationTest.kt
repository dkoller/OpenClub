package org.openclub.api.members

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.module
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID
import org.openclub.api.checkContract

/** Malformed-input and privilege-escalation matrix for the member directory and edit routes (#24). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberHttpInputAndEscalationTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val editorAccount = UUID.randomUUID()
    private val viewerAccount = UUID.randomUUID()
    private val outsiderAccount = UUID.randomUUID()
    private val editor = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val outsider = UUID.randomUUID()
    private val plainMember = UUID.randomUUID()
    private val archived = UUID.randomUUID()
    private val allMembers = arrayOf(editor, viewer, outsider, plainMember, archived)
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = when (handle) {
            "editor" -> ActiveSession(editorAccount, csrf)
            "viewer" -> ActiveSession(viewerAccount, csrf)
            "outsider" -> ActiveSession(outsiderAccount, csrf)
            else -> null
        }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    @BeforeAll
    fun createFixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val db = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        database = db
        fun member(tx: DSLContext, id: UUID, account: UUID?, first: String) {
            if (account != null) {
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                    UUID.randomUUID(), account, UUID.randomUUID().toString())
            }
            tx.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth,
                   email, phone) VALUES (?, ?, ?, 'Person', DATE '1990-01-01',
                   'person@example.invalid', '+41000000000')""", id, account, first)
        }
        try {
            runBlocking {
                db.onDatabaseThread { tx ->
                    tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                        club, "member-input-$club")
                    member(tx, editor, editorAccount, "Editor")
                    member(tx, viewer, viewerAccount, "Viewer")
                    member(tx, outsider, outsiderAccount, "Outsider")
                    member(tx, plainMember, null, "Plain")
                    member(tx, archived, null, "Archived")
                    tx.execute("UPDATE members SET archived_at = now() WHERE id = ?", archived)
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", editor)
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", editor)
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", viewer)
                }
            }
        } catch (failure: Throwable) {
            dropFixture()
            throw failure
        }
    }

    @AfterAll
    fun dropFixture() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM members WHERE first_name LIKE 'Mmatrix%'")
                tx.execute("DELETE FROM members WHERE id IN (?, ?, ?, ?, ?)", *allMembers)
                tx.execute("DELETE FROM accounts WHERE id IN (?, ?, ?)", editorAccount, viewerAccount, outsiderAccount)
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    /** No test in this class may create, rename, archive or otherwise change a member or a grant. */
    @AfterEach
    fun assertNothingChanged() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                val rows = tx.fetch(
                    "SELECT id, first_name, last_name, archived_at IS NOT NULL AS archived " +
                        "FROM members WHERE id IN (?, ?, ?, ?, ?)", *allMembers)
                    .associate { it.get("id", UUID::class.java) to
                        listOf(it.get("first_name", String::class.java), it.get("last_name", String::class.java),
                            it.get("archived", Boolean::class.java)) }
                assertEquals(mapOf(
                    editor to listOf("Editor", "Person", false), viewer to listOf("Viewer", "Person", false),
                    outsider to listOf("Outsider", "Person", false), plainMember to listOf("Plain", "Person", false),
                    archived to listOf("Archived", "Person", true)), rows, "members")
                assertEquals(0L, tx.fetchValue("SELECT COUNT(*) FROM members WHERE first_name LIKE 'Mmatrix%'")
                    ?.toString()?.toLong(), "members created")
                assertEquals(3L, tx.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id IN (?, ?, ?, ?, ?)", *allMembers)
                    ?.toString()?.toLong(), "grants")
            }
        }
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { checkContract();
            module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin,
                database = requireNotNull(database))
        }
        block()
    }

    /** Runs every case and reports all mismatches together, so one failure never hides another. */
    private inner class Cases(private val app: ApplicationTestBuilder) {
        private val failures = mutableListOf<String>()

        suspend fun send(
            expected: HttpStatusCode,
            label: String,
            method: HttpMethod,
            path: String,
            body: String? = null,
            handle: String = "editor",
            contentType: String? = "application/json",
        ) {
            val response = app.client.request(path) {
                this.method = method
                header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
                if (method != HttpMethod.Get) {
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }
                if (contentType != null && body != null) header(HttpHeaders.ContentType, contentType)
                if (body != null) setBody(body)
            }
            val text = response.bodyAsText()
            val name = "$label (${method.value} $path)"
            if (response.status != expected) failures += "$name: expected $expected but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$name: missing no-store"
            if (text.contains("person@example.invalid")) failures += "$name: leaked contact data"
        }

        fun assertNoFailures() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    private val json = "application/json"
    private fun newMember(vararg overrides: Pair<String, String?>): String {
        val fields = linkedMapOf<String, String?>(
            "firstName" to "\"Mmatrix New\"", "lastName" to "\"Person\"", "dateOfBirth" to "\"1990-01-01\"")
        for ((key, value) in overrides) if (value == null) fields.remove(key) else fields[key] = value
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    /** Bodies that must be rejected as malformed on create. The second element is the content type. */
    private fun malformedCreates(): List<Triple<String, String?, String?>> = listOf(
        Triple("text/plain", newMember(), "text/plain"),
        Triple("no content type", newMember(), null),
        Triple("empty body", "", json),
        Triple("truncated JSON", "{\"firstName\":", json),
        Triple("JSON array", "[]", json),
        Triple("JSON null", "null", json),
        Triple("missing firstName", newMember("firstName" to null), json),
        Triple("missing lastName", newMember("lastName" to null), json),
        Triple("missing dateOfBirth", newMember("dateOfBirth" to null), json),
        Triple("unknown key", newMember("role" to "\"ADMIN\""), json),
        Triple("numeric firstName", newMember("firstName" to "1"), json),
        Triple("null firstName", newMember("firstName" to "null"), json),
        Triple("blank firstName", newMember("firstName" to "\"   \""), json),
        Triple("201-character firstName", newMember("firstName" to "\"" + "a".repeat(201) + "\""), json),
        Triple("control character in lastName", newMember("lastName" to """"A\u0007B""""), json),
        Triple("non-date dateOfBirth", newMember("dateOfBirth" to "\"not-a-date\""), json),
        Triple("impossible dateOfBirth", newMember("dateOfBirth" to "\"2024-13-45\""), json),
        Triple("future dateOfBirth", newMember("dateOfBirth" to "\"2999-01-01\""), json),
        Triple("dateOfBirth before 1850", newMember("dateOfBirth" to "\"1800-01-01\""), json),
        Triple("numeric dateOfBirth", newMember("dateOfBirth" to "19900101"), json),
        Triple("email without @", newMember("email" to "\"nobody\""), json),
        Triple("email with two @", newMember("email" to "\"a@b@c\""), json),
        Triple("numeric email", newMember("email" to "5"), json),
        Triple("phone with control character", newMember("phone" to """"12\u0007""""), json),
        Triple("41-character phone", newMember("phone" to "\"" + "1".repeat(41) + "\""), json),
        Triple("201-character gender", newMember("gender" to "\"" + "g".repeat(201) + "\""), json),
        Triple("string isPlayer", newMember("isPlayer" to "\"yes\""), json),
        Triple("numeric isPlayer", newMember("isPlayer" to "1"), json),
        Triple("null isPlayer", newMember("isPlayer" to "null"), json),
        Triple("oversized body", newMember("note" to "\"" + "a".repeat(5000) + "\""), json),
    )

    /** Bodies that must be rejected as malformed on the name-only edit. */
    private fun malformedEdits(): List<Triple<String, String?, String?>> {
        fun names(first: String = "\"Mmatrix Edit\"", last: String = "\"Person\"", extra: String = "") =
            "{\"firstName\":$first,\"lastName\":$last$extra}"
        return listOf(
            Triple("text/plain", names(), "text/plain"),
            Triple("no content type", names(), null),
            Triple("empty body", "", json),
            Triple("truncated JSON", "{\"firstName\":", json),
            Triple("JSON array", "[]", json),
            Triple("JSON null", "null", json),
            Triple("missing lastName", "{\"firstName\":\"Mmatrix Edit\"}", json),
            Triple("unknown key", names(extra = ",\"dateOfBirth\":\"1990-01-01\""), json),
            Triple("numeric firstName", names(first = "1"), json),
            Triple("null lastName", names(last = "null"), json),
            Triple("blank firstName", names(first = "\"   \""), json),
            Triple("201-character lastName", names(last = "\"" + "a".repeat(201) + "\""), json),
            Triple("control character in firstName", names(first = """"A\u0007B""""), json),
            Triple("oversized body", names(extra = ",\"x\":\"" + "a".repeat(5000) + "\""), json),
        )
    }

    @Test
    fun malformedMemberRequestsFromAnEditorAre400() = app {
        val c = Cases(this)
        val bad = HttpStatusCode.BadRequest
        for ((label, body, type) in malformedCreates()) {
            c.send(bad, "create: $label", HttpMethod.Post, "/api/v1/members", body, contentType = type)
        }
        for ((label, body, type) in malformedEdits()) {
            c.send(bad, "edit: $label", HttpMethod.Patch, "/api/v1/members/$plainMember", body, contentType = type)
        }
        val ok = """{"firstName":"Mmatrix Edit","lastName":"Person"}"""
        c.send(bad, "edit non-UUID member", HttpMethod.Patch, "/api/v1/members/not-a-uuid", ok)
        c.send(bad, "archive non-UUID member", HttpMethod.Post, "/api/v1/members/not-a-uuid/archive")
        c.send(bad, "detail non-UUID member", HttpMethod.Get, "/api/v1/members/not-a-uuid")
        c.send(bad, "list with a 101-character q", HttpMethod.Get, "/api/v1/members?q=" + "a".repeat(101))
        c.send(bad, "list with a blank q", HttpMethod.Get, "/api/v1/members?q=%20%20%20")
        c.send(bad, "list with limit 0", HttpMethod.Get, "/api/v1/members?limit=0")
        c.send(bad, "list with limit 51", HttpMethod.Get, "/api/v1/members?limit=51")
        c.send(bad, "list with a non-numeric limit", HttpMethod.Get, "/api/v1/members?limit=abc")
        c.send(bad, "list with a garbage cursor", HttpMethod.Get, "/api/v1/members?cursor=garbage")
        // Valid requests that find nothing to act on are 404, not validation errors.
        val notFound = HttpStatusCode.NotFound
        for ((label, id) in listOf("unknown" to UUID.randomUUID(), "archived" to archived)) {
            c.send(notFound, "$label member detail", HttpMethod.Get, "/api/v1/members/$id")
            c.send(notFound, "$label member edit", HttpMethod.Patch, "/api/v1/members/$id", ok)
            c.send(notFound, "$label member archive", HttpMethod.Post, "/api/v1/members/$id/archive")
        }
        c.send(HttpStatusCode.OK, "valid list", HttpMethod.Get, "/api/v1/members")
        c.send(HttpStatusCode.OK, "valid detail", HttpMethod.Get, "/api/v1/members/$plainMember")
        c.assertNoFailures()
    }

    /** Without the grant the caller must not learn the expected shape: 403, never 400. */
    @Test
    fun malformedMemberRequestsFromCallersWithoutTheGrantAre403() = app {
        val c = Cases(this)
        val denied = HttpStatusCode.Forbidden
        val ok = """{"firstName":"Mmatrix Edit","lastName":"Person"}"""
        // The viewer may read the directory but not change it; the outsider may do neither.
        for (handle in listOf("viewer", "outsider")) {
            for ((label, body, type) in malformedCreates()) {
                c.send(denied, "$handle create: $label", HttpMethod.Post, "/api/v1/members", body, handle, type)
            }
            for ((label, body, type) in malformedEdits()) {
                c.send(denied, "$handle edit: $label", HttpMethod.Patch, "/api/v1/members/$plainMember",
                    body, handle, type)
            }
            c.send(denied, "$handle valid create", HttpMethod.Post, "/api/v1/members", newMember(), handle)
            c.send(denied, "$handle valid edit", HttpMethod.Patch, "/api/v1/members/$plainMember", ok, handle)
            c.send(denied, "$handle edit non-UUID member", HttpMethod.Patch, "/api/v1/members/not-a-uuid", ok, handle)
            c.send(denied, "$handle valid archive", HttpMethod.Post, "/api/v1/members/$plainMember/archive",
                handle = handle)
            c.send(denied, "$handle archive non-UUID member", HttpMethod.Post,
                "/api/v1/members/not-a-uuid/archive", handle = handle)
        }
        // The outsider also cannot read: bad and good queries and paths alike are 403.
        c.send(denied, "outsider valid list", HttpMethod.Get, "/api/v1/members", handle = "outsider")
        c.send(denied, "outsider list with limit 0", HttpMethod.Get, "/api/v1/members?limit=0", handle = "outsider")
        c.send(denied, "outsider list with a garbage cursor", HttpMethod.Get, "/api/v1/members?cursor=garbage",
            handle = "outsider")
        c.send(denied, "outsider valid detail", HttpMethod.Get, "/api/v1/members/$plainMember", handle = "outsider")
        c.send(denied, "outsider detail non-UUID member", HttpMethod.Get, "/api/v1/members/not-a-uuid",
            handle = "outsider")
        c.send(denied, "outsider detail of an unknown member", HttpMethod.Get, "/api/v1/members/${UUID.randomUUID()}",
            handle = "outsider")
        // The viewer reads normally.
        c.send(HttpStatusCode.OK, "viewer list", HttpMethod.Get, "/api/v1/members", handle = "viewer")
        c.send(HttpStatusCode.OK, "viewer detail", HttpMethod.Get, "/api/v1/members/$plainMember", handle = "viewer")
        c.send(HttpStatusCode.BadRequest, "viewer list with limit 0", HttpMethod.Get, "/api/v1/members?limit=0",
            handle = "viewer")
        c.send(HttpStatusCode.Unauthorized, "unknown session list", HttpMethod.Get, "/api/v1/members", handle = "ghost")
        c.send(HttpStatusCode.Unauthorized, "unknown session create", HttpMethod.Post, "/api/v1/members",
            newMember(), "ghost")
        c.assertNoFailures()
    }
}
