package org.openclub.api.club

import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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

/** Malformed-input and privilege-escalation matrix for the club settings writes (#24). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubSettingsHttpInputAndEscalationTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val clubName = "Settings matrix ${UUID.randomUUID().toString().take(8)}"
    private val adminAccount = UUID.randomUUID()
    private val adminNoGrantAccount = UUID.randomUUID()
    private val editorAccount = UUID.randomUUID()
    private val outsiderAccount = UUID.randomUUID()
    private val admin = UUID.randomUUID()
    private val adminNoGrant = UUID.randomUUID()
    private val editor = UUID.randomUUID()
    private val outsider = UUID.randomUUID()
    private val allMembers = arrayOf(admin, adminNoGrant, editor, outsider)
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = when (handle) {
            "admin" -> ActiveSession(adminAccount, csrf)
            "adminnogrant" -> ActiveSession(adminNoGrantAccount, csrf)
            "editor" -> ActiveSession(editorAccount, csrf)
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
        fun member(tx: DSLContext, id: UUID, account: UUID, role: String) {
            tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
            tx.execute(
                """INSERT INTO account_identities (id, account_id, issuer, subject)
                   VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                UUID.randomUUID(), account, UUID.randomUUID().toString())
            tx.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth,
                   email, phone, role) VALUES (?, ?, 'Synthetic', 'Person', DATE '1990-01-01',
                   'person@example.invalid', '+41000000000', ?)""", id, account, role)
        }
        try {
            runBlocking {
                db.onDatabaseThread { tx ->
                    tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)",
                        club, clubName, "settings-input-$club")
                    member(tx, admin, adminAccount, "ADMIN")
                    member(tx, adminNoGrant, adminNoGrantAccount, "ADMIN")
                    member(tx, editor, editorAccount, "MEMBER")
                    member(tx, outsider, outsiderAccount, "MEMBER")
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", admin)
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", editor)
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
                tx.execute("DELETE FROM members WHERE id IN (?, ?, ?, ?)", *allMembers)
                tx.execute("DELETE FROM accounts WHERE id IN (?, ?, ?, ?)",
                    adminAccount, adminNoGrantAccount, editorAccount, outsiderAccount)
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    /** No test in this class may change a club setting or a grant. */
    @AfterEach
    fun assertNothingChanged() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                val row = requireNotNull(tx.fetchOne(
                    "SELECT name, gender_required_for_players, adult_age_years FROM clubs WHERE id = ?", club))
                assertEquals(clubName, row.get("name", String::class.java), "club name")
                assertEquals(false, row.get("gender_required_for_players", Boolean::class.java), "gender policy")
                assertEquals(18, row.get("adult_age_years", Int::class.java), "adult age")
                assertEquals(2L, tx.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id IN (?, ?, ?, ?)", *allMembers)
                    ?.toString()?.toLong(), "grants")
            }
        }
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin,
                database = requireNotNull(database))
        }
        block()
    }

    /** Runs every case and reports all mismatches together, so one failure never hides another. */
    private inner class Cases(private val app: ApplicationTestBuilder) {
        private val failures = mutableListOf<String>()

        suspend fun patch(
            expected: HttpStatusCode,
            label: String,
            path: String,
            body: String?,
            handle: String = "admin",
            contentType: String? = "application/json",
        ) {
            val response = app.client.patch(path) {
                header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
                header(HttpHeaders.Origin, origin)
                header("X-CSRF-Token", csrf)
                if (contentType != null && body != null) header(HttpHeaders.ContentType, contentType)
                if (body != null) setBody(body)
            }
            val text = response.bodyAsText()
            val name = "$label (PATCH $path)"
            if (response.status != expected) failures += "$name: expected $expected but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$name: missing no-store"
            if (text.contains("person@example.invalid")) failures += "$name: leaked contact data"
        }

        fun assertNoFailures() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    private val json = "application/json"
    private val namePath = "/api/v1/club"
    private val genderPath = "/api/v1/club/player-gender-policy"
    private val agePath = "/api/v1/club/adult-age-policy"

    /** Malformed bodies for one route, plus the route-specific bad values for its single field. */
    private fun malformed(field: String, validValue: String, badValues: List<Pair<String, String>>):
        List<Triple<String, String?, String?>> {
        val ok = "{\"$field\":$validValue}"
        return listOf(
            Triple("text/plain", ok, "text/plain"),
            Triple("no content type", ok, null),
            Triple("empty body", "", json),
            Triple("truncated JSON", "{\"$field\":", json),
            Triple("JSON array", "[]", json),
            Triple("JSON null", "null", json),
            Triple("missing field", "{}", json),
            Triple("unknown key", "{\"$field\":$validValue,\"role\":\"ADMIN\"}", json),
            Triple("oversized body", "{\"$field\":$validValue,\"x\":\"" + "a".repeat(5000) + "\"}", json),
        ) + badValues.map { (label, value) -> Triple(label, "{\"$field\":$value}", json) }
    }

    private val nameCases = malformed("name", "\"Mmatrix Club\"", listOf(
        "numeric name" to "1", "null name" to "null", "blank name" to "\"   \"",
        "201-character name" to "\"" + "a".repeat(201) + "\"", "control character in name" to """"A\u0007B"""",
    ))
    private val genderCases = malformed("genderRequiredForPlayers", "true", listOf(
        "string boolean" to "\"true\"", "numeric boolean" to "1", "null value" to "null", "yes string" to "\"yes\"",
    ))
    private val ageCases = malformed("adultAgeYears", "18", listOf(
        "string age" to "\"18\"", "fractional age" to "18.5", "age 12" to "12", "age 26" to "26",
        "age 0" to "0", "negative age" to "-1", "null value" to "null", "text age" to "\"abc\"",
        "exponent age" to "1e1", "boolean age" to "true",
    ))

    private val routes: List<Triple<String, List<Triple<String, String?, String?>>, String>> = listOf(
        Triple(namePath, nameCases, """{"name":"Mmatrix Club"}"""),
        Triple(genderPath, genderCases, """{"genderRequiredForPlayers":true}"""),
        Triple(agePath, ageCases, """{"adultAgeYears":21}"""),
    )

    @Test
    fun malformedSettingsFromAnAdminAre400() = app {
        val c = Cases(this)
        for ((path, cases, _) in routes) {
            for ((label, body, type) in cases) {
                c.patch(HttpStatusCode.BadRequest, label, path, body, contentType = type)
            }
        }
        c.assertNoFailures()
    }

    /** Without an ADMIN holding MANAGE_PERMISSIONS the caller must not learn the expected shape: 403. */
    @Test
    fun settingsRequestsFromCallersWithoutTheAdminGrantAre403() = app {
        val c = Cases(this)
        val denied = HttpStatusCode.Forbidden
        // An ADMIN role without the grant, a member who may edit members, and a member with no grant.
        for (handle in listOf("adminnogrant", "editor", "outsider")) {
            for ((path, cases, valid) in routes) {
                for ((label, body, type) in cases) {
                    c.patch(denied, "$handle $label", path, body, handle, type)
                }
                c.patch(denied, "$handle valid request", path, valid, handle)
            }
        }
        for ((path, _, valid) in routes) {
            c.patch(HttpStatusCode.Unauthorized, "unknown session", path, valid, "ghost")
        }
        c.assertNoFailures()
    }
}
