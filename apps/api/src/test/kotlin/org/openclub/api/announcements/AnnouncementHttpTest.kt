package org.openclub.api.announcements

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
import kotlinx.serialization.json.JsonNull
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

/** Announcements (OpenAPI 1.14.0): who posts where, and who sees what. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnouncementHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val teamA = UUID.randomUUID()
    private val teamB = UUID.randomUUID()
    private val kid = UUID.randomUUID()
    private val tag = "Ann" + UUID.randomUUID().toString().take(6)
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { tx ->
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Announcement HTTP Club', ?)", club, "announcement-http-$club")
            tx.execute("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)", teamA, "$tag A", teamB, "$tag B")
            for (handle in listOf("board", "coach", "player", "guardian", "plain", "stranger")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "ann-$account")
                if (handle == "stranger") continue
                memberOf[handle] = UUID.randomUUID().also {
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                           VALUES (?, ?, ?, ?, DATE '1980-01-01', 'n@example.invalid', '+41000000050')""", it, account, handle, tag)
                }
            }
            tx.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'POST_ANNOUNCEMENTS')", memberOf.getValue("board"))
            tx.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')", teamA, memberOf.getValue("coach"))
            tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamA, memberOf.getValue("player"))
            tx.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', ?, DATE '2016-02-03')", kid, tag)
            tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamB, kid)
            tx.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM announcements WHERE title LIKE ?", "$tag%")
                tx.execute("DELETE FROM teams WHERE id IN (?, ?)", teamA, teamB)
                tx.execute("DELETE FROM members WHERE last_name = ?", tag)
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

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod, path: String, handle: String?, body: String? = null,
        withOrigin: String? = origin, withCsrf: String? = csrf,
    ): HttpResponse = client.request(path) {
        this.method = method
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        if (method != HttpMethod.Get) {
            if (withOrigin != null) header(HttpHeaders.Origin, withOrigin)
            if (withCsrf != null) header("X-CSRF-Token", withCsrf)
        }
        if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private class Check { val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
        }
        fun done() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    private fun post(team: UUID?, title: String, extra: String = "") =
        """{"teamId":${team?.let { "\"$it\"" } ?: "null"},"title":"$tag $title","body":"Line one\nLine two"$extra}"""

    private suspend fun ApplicationTestBuilder.create(handle: String, team: UUID?, title: String): String {
        val response = send(HttpMethod.Post, "/api/v1/announcements", handle, post(team, title))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json().getValue("id").jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.titles(handle: String) =
        send(HttpMethod.Get, "/api/v1/me/announcements", handle).json().getValue("items").jsonArray
            .map { it.jsonObject.getValue("title").jsonPrimitive.content }.filter { it.startsWith(tag) }.map { it.removePrefix("$tag ") }

    @Test
    fun theBoardPostsClubWideCoachesTheirTeamAndEveryoneSeesTheirOwn() = app {
        sql { it.execute("DELETE FROM announcements WHERE title LIKE ?", "$tag%") }
        val check = Check()
        check.expect("no session", send(HttpMethod.Post, "/api/v1/announcements", null, post(null, "x")), HttpStatusCode.Unauthorized)
        check.expect("wrong origin", send(HttpMethod.Post, "/api/v1/announcements", "board", post(null, "x"), withOrigin = "https://evil.example.invalid"), HttpStatusCode.Forbidden)
        check.expect("player, bad body", send(HttpMethod.Post, "/api/v1/announcements", "player", "[]"), HttpStatusCode.Forbidden)
        for ((label, body) in listOf(
            "no teamId key" to """{"title":"$tag x","body":"y"}""",
            "blank title" to """{"teamId":null,"title":"  ","body":"y"}""",
            "long body" to """{"teamId":null,"title":"$tag x","body":"${"y".repeat(5001)}"}""",
            "past date" to post(null, "x", ""","showUntil":"2000-01-01""""),
            "too far" to post(null, "x", ""","showUntil":"${java.time.LocalDate.now().plusYears(2)}""""),
            "extra key" to post(null, "x", ""","pinned":true"""),
        )) check.expect(label, send(HttpMethod.Post, "/api/v1/announcements", "board", body), HttpStatusCode.BadRequest)
        check.expect("coach club-wide", send(HttpMethod.Post, "/api/v1/announcements", "coach", post(null, "x")), HttpStatusCode.Forbidden)
        check.expect("coach other team", send(HttpMethod.Post, "/api/v1/announcements", "coach", post(teamB, "x")), HttpStatusCode.Forbidden)
        check.expect("board for a team", send(HttpMethod.Post, "/api/v1/announcements", "board", post(teamA, "x")), HttpStatusCode.Forbidden)
        // A team-scoped coach cannot tell an unknown team from a foreign one.
        check.expect("unknown team", send(HttpMethod.Post, "/api/v1/announcements", "coach", post(UUID.randomUUID(), "x")), HttpStatusCode.Forbidden)
        check.done()
        assertEquals(0, sql { (it.fetchValue("SELECT COUNT(*) FROM announcements WHERE title LIKE ?", "$tag%") as Number).toInt() })

        val long = send(HttpMethod.Post, "/api/v1/announcements", "board",
            """{"teamId":null,"title":"$tag Long","body":"${"ä".repeat(4999)}\n"}""")
        assertEquals(HttpStatusCode.Created, long.status, "5,000 characters with umlauts fit")
        sql { it.execute("DELETE FROM announcements WHERE title = ?", "$tag Long") }
        val clubWide = create("board", null, "Club")
        val teamPost = create("coach", teamA, "Team A")
        sql { it.execute("INSERT INTO announcements (id, team_id, title, body, show_until) VALUES (?, ?, ?, 'x', current_date)", UUID.randomUUID(), teamB, "$tag Team B") }
        sql { it.execute("INSERT INTO announcements (id, team_id, title, body, show_until) VALUES (?, NULL, ?, 'x', current_date - 1)", UUID.randomUUID(), "$tag Expired") }

        assertEquals(setOf("Club", "Team A"), titles("player").toSet())
        assertEquals(setOf("Club", "Team B"), titles("guardian").toSet(), "a guardian sees the child's team")
        assertEquals(setOf("Club"), titles("plain").toSet())
        assertEquals(setOf("Club", "Team A"), titles("coach").toSet(), "a coach sees the team they manage")
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/me/announcements", "stranger").status)

        val coachView = send(HttpMethod.Get, "/api/v1/me/announcements", "coach").json()
        val manageable = coachView.getValue("items").jsonArray.map { it.jsonObject }.associate {
            it.getValue("title").jsonPrimitive.content.removePrefix("$tag ") to it.getValue("canManage").jsonPrimitive.content.toBoolean() }
        assertEquals(mapOf("Club" to false, "Team A" to true), manageable.filterKeys { it in setOf("Club", "Team A") })
        assertEquals(listOf(teamA.toString()), coachView.getValue("audiences").jsonArray.map { it.jsonObject.getValue("teamId").jsonPrimitive.content })
        assertEquals(JsonNull, send(HttpMethod.Get, "/api/v1/me/announcements", "board").json()
            .getValue("audiences").jsonArray.single().jsonObject.getValue("teamId"))
        assertEquals(0, send(HttpMethod.Get, "/api/v1/me/announcements", "player").json().getValue("audiences").jsonArray.size)
        val item = send(HttpMethod.Get, "/api/v1/me/announcements", "player").json().getValue("items").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == teamPost }
        assertEquals(setOf("id", "teamId", "teamName", "title", "body", "showUntil", "createdAt", "canManage"), item.keys)
        assertEquals("Line one\nLine two", item.getValue("body").jsonPrimitive.content)

        val edit = """{"title":"$tag Team A edited","body":"New"}"""
        val check2 = Check()
        check2.expect("coach edits club post", send(HttpMethod.Put, "/api/v1/announcements/$clubWide", "coach", edit), HttpStatusCode.Forbidden)
        check2.expect("board edits team post", send(HttpMethod.Put, "/api/v1/announcements/$teamPost", "board", edit), HttpStatusCode.Forbidden)
        check2.expect("unknown, team-scoped", send(HttpMethod.Put, "/api/v1/announcements/${UUID.randomUUID()}", "coach", edit), HttpStatusCode.Forbidden)
        check2.expect("unknown, club-wide", send(HttpMethod.Put, "/api/v1/announcements/${UUID.randomUUID()}", "board", edit), HttpStatusCode.NotFound)
        check2.expect("bad id", send(HttpMethod.Delete, "/api/v1/announcements/x", "coach"), HttpStatusCode.BadRequest)
        check2.expect("coach edits own team", send(HttpMethod.Put, "/api/v1/announcements/$teamPost", "coach", edit), HttpStatusCode.NoContent)
        check2.expect("board deletes club post", send(HttpMethod.Delete, "/api/v1/announcements/$clubWide", "board"), HttpStatusCode.NoContent)
        check2.expect("deleted again", send(HttpMethod.Delete, "/api/v1/announcements/$clubWide", "board"), HttpStatusCode.NotFound)
        check2.done()
        assertEquals(setOf("Team A edited"), titles("player").toSet())

        // A revoked grant takes effect at once.
        sql { it.execute("DELETE FROM team_capability_grants WHERE member_id = ?", memberOf.getValue("coach")) }
        try {
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Delete, "/api/v1/announcements/$teamPost", "coach").status)
        } finally {
            sql { it.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')", teamA, memberOf.getValue("coach")) }
        }
    }
}
