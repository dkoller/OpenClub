package org.openclub.api.calendar

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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

/** Calendar feeds (OpenAPI 1.17.0): one secret link per member, shown once, ending with revocation, archive or unlink. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CalendarFeedHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val team = UUID.randomUUID()
    private val otherTeam = UUID.randomUUID()
    private val kid = UUID.randomUUID()
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
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
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Calendar HTTP Club', ?)", club, "calendar-http-$club")
            tx.execute("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)",
                team, "Calendar A ${team.toString().take(8)}", otherTeam, "Calendar B ${otherTeam.toString().take(8)}")
            // "player" plays in A; "guardian" has a child in B; "other" has no team; "stranger" has no member record.
            for (handle in listOf("player", "guardian", "other", "stranger")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "calendar-$account")
                if (handle == "stranger") continue
                memberOf[handle] = UUID.randomUUID().also {
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                           VALUES (?, ?, ?, 'Calendar', DATE '1980-01-01', 'c@example.invalid', '+41000000040')""", it, account, handle)
                }
            }
            tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, memberOf.getValue("player"))
            tx.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Calendar', CURRENT_DATE - 3650)", kid)
            tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", otherTeam, kid)
            tx.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
            fun event(teamId: UUID, title: String, offset: String, notes: String?) = tx.execute(
                """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, notes)
                   VALUES (?, ?, 'TRAINING', ?, now() + ?::interval, now() + ?::interval + INTERVAL '90 minutes', ?)""",
                UUID.randomUUID(), teamId, title, offset, offset, notes)
            event(team, "Next week", "7 days", "Bring shoes")
            event(team, "Last month", "-60 days", null)       // before the four-week window
            event(team, "Next year", "400 days", null)        // after the six-month window
            event(otherTeam, "Kids cup", "10 days", null)
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM teams WHERE id IN (?, ?)", team, otherTeam)
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", (memberOf.values + kid).toTypedArray())
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

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, handle: String?, withCsrf: String? = csrf): HttpResponse =
        client.request(path) {
            this.method = method
            if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            if (method != HttpMethod.Get) {
                header(HttpHeaders.Origin, origin)
                if (withCsrf != null) header("X-CSRF-Token", withCsrf)
            }
        }

    private suspend fun ApplicationTestBuilder.create(handle: String): String {
        val response = send(HttpMethod.Post, "/api/v1/me/calendar-feed", handle)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val url = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("url").jsonPrimitive.content
        assertTrue(url.startsWith("$origin/api/v1/calendar/"))
        return url.removePrefix(origin)
    }

    @Test
    fun aMemberCreatesAFeedWithTheirOwnAndTheirChildrensEvents() = app {
        val path = create("player")
        val secret = path.substringAfterLast('/')
        assertEquals(CalendarFeeds.digest(secret),
            sql { it.fetchValue("SELECT secret_hash FROM calendar_feeds WHERE member_id = ?", memberOf.getValue("player")) },
            "only the hash is stored")
        val status = Json.parseToJsonElement(send(HttpMethod.Get, "/api/v1/me/calendar-feed", "player").bodyAsText()).jsonObject
        assertTrue(status.getValue("active").jsonPrimitive.boolean)
        assertFalse(secret in status.toString(), "the link is shown only once")

        val feed = send(HttpMethod.Get, path, null)
        assertEquals(HttpStatusCode.OK, feed.status)
        assertTrue(feed.headers[HttpHeaders.ContentType]!!.startsWith("text/calendar"))
        assertEquals("private, no-store", feed.headers[HttpHeaders.CacheControl])
        val text = feed.bodyAsText()
        assertTrue("SUMMARY:Calendar A ${team.toString().take(8)}: Next week" in text)
        assertTrue("DESCRIPTION:Bring shoes" in text, "notes are included (owner decision)")
        assertFalse("Last month" in text || "Next year" in text, "only four weeks back to six months ahead")
        assertFalse("Kids cup" in text, "another team's event is not in the player's feed")
        assertFalse("Calendar" in text.lines().filter { it.startsWith("DESCRIPTION") || it.startsWith("LOCATION") }.joinToString())

        val guardianFeed = send(HttpMethod.Get, create("guardian"), null).bodyAsText()
        assertTrue("Kids cup" in guardianFeed && "Next week" !in guardianFeed, "a guardian's feed has their child's events")
        assertFalse("Kiddo" in guardianFeed, "no names")
        val empty = send(HttpMethod.Get, create("other"), null).bodyAsText()
        assertFalse("BEGIN:VEVENT" in empty, "a member without a team gets an empty calendar")
    }

    @Test
    fun aFeedEndsWhenReplacedRevokedArchivedOrUnlinked() = app {
        val first = create("player")
        val second = create("player")
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, first, null).status, "a new link replaces the old one")
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, second, null).status)
        assertEquals(1, count("SELECT COUNT(*) FROM calendar_feeds WHERE member_id = ?", memberOf.getValue("player")))

        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/me/calendar-feed", "player").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, second, null).status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/me/calendar-feed", "player").status, "revoking again")

        // Losing the verified identity stops the feed at once; the link itself goes when the login link changes.
        val third = create("player")
        val identities = sql { it.fetch("DELETE FROM account_identities WHERE account_id = ? RETURNING id, issuer, subject", accounts.getValue("player")) }
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, third, null).status)
        sql { tx -> identities.forEach {
            tx.execute("INSERT INTO account_identities (id, account_id, issuer, subject) VALUES (?, ?, ?, ?)",
                it.get(0), accounts.getValue("player"), it.get(1), it.get(2))
        } }
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, third, null).status)
        sql { it.execute("UPDATE members SET account_id = NULL WHERE id = ?", memberOf.getValue("player")) }
        sql { it.execute("UPDATE members SET account_id = ? WHERE id = ?", accounts.getValue("player"), memberOf.getValue("player")) }
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, third, null).status, "unlinking deletes the feed for good")
        assertEquals(0, count("SELECT COUNT(*) FROM calendar_feeds WHERE member_id = ?", memberOf.getValue("player")))
    }

    @Test
    fun refusalsRevealNothing() = app {
        val check = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) check += "$label: expected $status but was ${response.status}"
        }
        expect("status without session", send(HttpMethod.Get, "/api/v1/me/calendar-feed", null), HttpStatusCode.Unauthorized)
        expect("status without member", send(HttpMethod.Get, "/api/v1/me/calendar-feed", "stranger"), HttpStatusCode.Forbidden)
        expect("create without CSRF", send(HttpMethod.Post, "/api/v1/me/calendar-feed", "other", withCsrf = null), HttpStatusCode.Forbidden)
        expect("create without member", send(HttpMethod.Post, "/api/v1/me/calendar-feed", "stranger"), HttpStatusCode.Forbidden)
        expect("revoke without member", send(HttpMethod.Delete, "/api/v1/me/calendar-feed", "stranger"), HttpStatusCode.Forbidden)
        expect("unknown secret", send(HttpMethod.Get, "/api/v1/calendar/${CalendarFeeds.newSecret()}", null), HttpStatusCode.NotFound)
        expect("malformed secret", send(HttpMethod.Get, "/api/v1/calendar/short", null), HttpStatusCode.NotFound)
        expect("hash instead of secret", send(HttpMethod.Get, "/api/v1/calendar/${"a".repeat(64)}", null), HttpStatusCode.NotFound)
        assertTrue(check.isEmpty(), check.joinToString("\n", "\n"))
        val unknown = send(HttpMethod.Get, "/api/v1/calendar/${CalendarFeeds.newSecret()}", null)
        assertEquals("""{"code":"not_found"}""", unknown.bodyAsText())
        assertEquals("private, no-store", unknown.headers[HttpHeaders.CacheControl])
    }
}
