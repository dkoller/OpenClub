package org.openclub.api.teams

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Volunteer shifts (OpenAPI 1.16.0, unfilled overview 1.23.0): who manages, who sees names, who signs up whom, capacity, closing, the order of
 * answers and leaving the team. Each test creates its own event, so the tests do not depend on each other's order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventShiftHttpTest {
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
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Shift HTTP Club', ?)", club, "shift-http-$club")
            tx.execute("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)",
                teamA, "Shift test A ${teamA.toString().take(8)}", teamB, "Shift test B ${teamB.toString().take(8)}")
            // teamManager manages A; viewer views A; player and second play in A; guardian's kid plays in A;
            // outsider plays in B only; plain is a member without a team; stranger has no member record.
            for (handle in listOf("clubManager", "teamManager", "viewer", "player", "second", "guardian", "outsider", "plain", "stranger")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "shift-$account")
                if (handle == "stranger") continue
                memberOf[handle] = UUID.randomUUID().also {
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                           VALUES (?, ?, ?, 'Shifts', DATE '1980-01-01', 's@example.invalid', '+41000000030')""", it, account, handle)
                }
            }
            tx.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'MANAGE_TEAMS')", memberOf.getValue("clubManager"))
            tx.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')", teamA, memberOf.getValue("teamManager"))
            tx.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'VIEW_TEAMS')", teamA, memberOf.getValue("viewer"))
            for (handle in listOf("player", "second")) tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamA, memberOf.getValue(handle))
            tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamB, memberOf.getValue("outsider"))
            tx.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Shifts', CURRENT_DATE - 3650)", kid)
            tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamA, kid)
            tx.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM teams WHERE id IN (?, ?)", teamA, teamB)
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

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod, path: String, handle: String?, body: String? = null, withCsrf: String? = csrf,
    ): HttpResponse = client.request(path) {
        this.method = method
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        if (method != HttpMethod.Get) {
            header(HttpHeaders.Origin, origin)
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

    private val start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5).withHour(14).withMinute(0).withSecond(0).withNano(0)

    /** An event of team A starting at [from] (default in five days), created directly in the database. */
    private fun event(from: OffsetDateTime = start, team: UUID = teamA): UUID = UUID.randomUUID().also {
        sql { db -> db.execute(
            """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at)
               VALUES (?, ?, 'MATCH', 'Shift match', ?::timestamptz, ?::timestamptz)""",
            it, team, from.toString(), from.plusHours(3).toString()) }
    }

    private fun shift(title: String = "Bar", from: OffsetDateTime = start, hours: Long = 2, needed: Int = 2) =
        """{"title":"$title","startsAt":"$from","endsAt":"${from.plusHours(hours)}","needed":$needed}"""

    private suspend fun ApplicationTestBuilder.create(event: UUID, body: String = shift(), handle: String = "teamManager"): String {
        val response = send(HttpMethod.Post, "/api/v1/events/$event/shifts", handle, body)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json().getValue("id").jsonPrimitive.content
    }

    private fun volunteer(shift: String, member: UUID) = "/api/v1/shifts/$shift/volunteers/$member"

    @Test
    fun unfilledOverviewListsOpenPlacesOfManagedTeamsOnly() = app {
        val soon = event()
        val otherTeam = event(team = teamB)
        val later = event(from = start.plusDays(15))
        val cancelled = event()
        val open = create(soon, shift(title = "Bar", needed = 2))
        val full = create(soon, shift(title = "Kasse", needed = 1))
        val foreign = create(otherTeam, shift(title = "Grill"), handle = "clubManager")
        val tooLate = create(later, shift(title = "Bar", from = start.plusDays(15)))
        val dropped = create(cancelled, shift(title = "Bar"))
        sql { it.execute("UPDATE team_events SET cancelled = TRUE WHERE id = ?", cancelled) }
        val check = Check()
        check.expect("sign up", send(HttpMethod.Put, volunteer(open, memberOf.getValue("player")), "player"), HttpStatusCode.NoContent)
        check.expect("fill", send(HttpMethod.Put, volunteer(full, memberOf.getValue("second")), "second"), HttpStatusCode.NoContent)
        check.expect("no session", send(HttpMethod.Get, "/api/v1/shifts/unfilled", null), HttpStatusCode.Unauthorized)
        for (handle in listOf("stranger", "plain", "player", "viewer", "guardian")) {
            check.expect(handle, send(HttpMethod.Get, "/api/v1/shifts/unfilled", handle), HttpStatusCode.Forbidden)
        }
        check.done()

        suspend fun listed(handle: String): Map<String, JsonObject> {
            val response = send(HttpMethod.Get, "/api/v1/shifts/unfilled", handle)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val body = response.json()
            assertEquals(setOf("items", "truncated"), body.keys)
            assertFalse(body.getValue("truncated").jsonPrimitive.boolean)
            return body.getValue("items").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
        }
        val teamView = listed("teamManager")
        assertTrue(open in teamView, "a shift with a free place is listed")
        for (absent in listOf(full, foreign, tooLate, dropped)) assertFalse(absent in teamView, "not listed: $absent")
        val item = teamView.getValue(open)
        assertEquals(setOf("id", "title", "startsAt", "endsAt", "needed", "filled", "eventId", "eventTitle", "teamId", "teamName"), item.keys,
            "counts only, never who signed up")
        assertEquals(2, item.getValue("needed").jsonPrimitive.int)
        assertEquals(1, item.getValue("filled").jsonPrimitive.int)
        assertEquals(soon.toString(), item.getValue("eventId").jsonPrimitive.content)
        assertEquals(teamA.toString(), item.getValue("teamId").jsonPrimitive.content)
        val clubView = listed("clubManager")
        assertTrue(open in clubView && foreign in clubView, "a club-wide manager sees every team")
        for (absent in listOf(full, tooLate, dropped)) assertFalse(absent in clubView, "not listed: $absent")
    }

    @Test
    fun managersCreateChangeAndDeleteShiftsOfTheirTeamOnly() = app {
        val event = event()
        val other = event(team = teamB)
        val check = Check()
        check.expect("no session", send(HttpMethod.Post, "/api/v1/events/$event/shifts", null, shift()), HttpStatusCode.Unauthorized)
        check.expect("no CSRF", send(HttpMethod.Post, "/api/v1/events/$event/shifts", "teamManager", shift(), withCsrf = null), HttpStatusCode.Forbidden)
        check.expect("no member record", send(HttpMethod.Post, "/api/v1/events/$event/shifts", "stranger", shift()), HttpStatusCode.Forbidden)
        check.expect("viewer", send(HttpMethod.Post, "/api/v1/events/$event/shifts", "viewer", shift()), HttpStatusCode.Forbidden)
        check.expect("player", send(HttpMethod.Post, "/api/v1/events/$event/shifts", "player", shift()), HttpStatusCode.Forbidden)
        check.expect("other team's event", send(HttpMethod.Post, "/api/v1/events/$other/shifts", "teamManager", shift()), HttpStatusCode.Forbidden)
        check.expect("unknown event, team-scoped", send(HttpMethod.Post, "/api/v1/events/${UUID.randomUUID()}/shifts", "teamManager", shift()), HttpStatusCode.Forbidden)
        check.expect("unknown event, club-wide", send(HttpMethod.Post, "/api/v1/events/${UUID.randomUUID()}/shifts", "clubManager", shift()), HttpStatusCode.NotFound)
        check.expect("bad id", send(HttpMethod.Post, "/api/v1/events/nope/shifts", "teamManager", shift()), HttpStatusCode.BadRequest)
        for ((label, body) in listOf(
            "no places" to shift(needed = 0), "too many places" to shift(needed = 101),
            "places as text" to """{"title":"Bar","startsAt":"$start","endsAt":"${start.plusHours(1)}","needed":"2"}""",
            "longer than a day" to shift(hours = 25), "blank title" to shift(title = "  "),
            "extra key" to """{"title":"Bar","startsAt":"$start","endsAt":"${start.plusHours(1)}","needed":2,"x":1}""",
            "far from the event" to shift(from = start.plusDays(3)),
        )) check.expect(label, send(HttpMethod.Post, "/api/v1/events/$event/shifts", "teamManager", body), HttpStatusCode.BadRequest)
        check.done()
        assertEquals(0, count("SELECT COUNT(*) FROM event_shifts WHERE event_id IN (?, ?)", event, other), "nothing was created")

        val id = create(event, shift(title = " Bar "))
        assertEquals("Bar", sql { it.fetchValue("SELECT title FROM event_shifts WHERE id = ?::uuid", id) }, "the title is trimmed")
        check.expect("viewer changes", send(HttpMethod.Put, "/api/v1/shifts/$id", "viewer", shift(title = "Grill")), HttpStatusCode.Forbidden)
        check.expect("player deletes", send(HttpMethod.Delete, "/api/v1/shifts/$id", "player"), HttpStatusCode.Forbidden)
        check.expect("unknown shift, team-scoped", send(HttpMethod.Put, "/api/v1/shifts/${UUID.randomUUID()}", "teamManager", shift()), HttpStatusCode.Forbidden)
        check.expect("unknown shift, club-wide", send(HttpMethod.Delete, "/api/v1/shifts/${UUID.randomUUID()}", "clubManager"), HttpStatusCode.NotFound)
        check.expect("change", send(HttpMethod.Put, "/api/v1/shifts/$id", "teamManager", shift(title = "Grill", needed = 1)), HttpStatusCode.NoContent)
        check.expect("change too far", send(HttpMethod.Put, "/api/v1/shifts/$id", "teamManager", shift(from = start.minusDays(2))), HttpStatusCode.BadRequest)
        check.expect("sign up", send(HttpMethod.Put, volunteer(id, memberOf.getValue("player")), "player"), HttpStatusCode.NoContent)
        check.expect("fewer places than volunteers", send(HttpMethod.Put, "/api/v1/shifts/$id", "clubManager", shift(needed = 1)), HttpStatusCode.NoContent)
        check.expect("club manager deletes", send(HttpMethod.Delete, "/api/v1/shifts/$id", "clubManager"), HttpStatusCode.NoContent)
        check.done()
        assertEquals(0, count("SELECT COUNT(*) FROM shift_volunteers WHERE shift_id = ?::uuid", id), "sign-ups go with the shift")

        val second = create(event)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, volunteer(second, memberOf.getValue("player")), "player").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, volunteer(second, memberOf.getValue("second")), "second").status)
        val conflict = send(HttpMethod.Put, "/api/v1/shifts/$second", "teamManager", shift(needed = 1))
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals(2, count("SELECT needed FROM event_shifts WHERE id = ?::uuid", second), "a refused change changes nothing")
        sql { it.execute("DELETE FROM team_events WHERE id IN (?, ?)", event, other) }
        assertEquals(0, count("SELECT COUNT(*) FROM event_shifts WHERE id = ?::uuid", second), "shifts go with their event")
    }

    @Test
    fun theTeamSeesShiftsWithNamesAndOthersSeeNothing() = app {
        val event = event()
        val id = create(event, shift(needed = 3))
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, volunteer(id, memberOf.getValue("player")), "player").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, volunteer(id, kid), "guardian").status)
        val path = "/api/v1/events/$event/shifts"

        val asPlayer = send(HttpMethod.Get, path, "player").json()
        assertEquals(setOf("items", "people", "canManage"), asPlayer.keys)
        assertFalse(asPlayer.getValue("canManage").jsonPrimitive.boolean)
        val item = asPlayer.getValue("items").jsonArray.single().jsonObject
        assertEquals(setOf("id", "title", "startsAt", "endsAt", "needed", "volunteers", "signedUp"), item.keys)
        assertEquals(3, item.getValue("needed").jsonPrimitive.int)
        assertEquals(listOf("player Shifts", "Kiddo S."), item.getValue("volunteers").jsonArray.map { it.jsonPrimitive.content },
            "public display names in sign-up order; a minor's surname is only an initial")
        assertEquals(listOf(memberOf.getValue("player").toString()), item.getValue("signedUp").jsonArray.map { it.jsonPrimitive.content },
            "only the caller's own people are identified")
        assertEquals(listOf(memberOf.getValue("player").toString()),
            asPlayer.getValue("people").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })

        val asGuardian = send(HttpMethod.Get, path, "guardian").json()
        assertEquals(listOf(kid.toString()), asGuardian.getValue("people").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content },
            "a guardian outside the team acts for their child")
        assertEquals(listOf(kid.toString()), asGuardian.getValue("items").jsonArray.single().jsonObject.getValue("signedUp").jsonArray.map { it.jsonPrimitive.content })
        val asViewer = send(HttpMethod.Get, path, "viewer").json()
        assertTrue(asViewer.getValue("people").jsonArray.isEmpty())
        assertEquals(2, asViewer.getValue("items").jsonArray.single().jsonObject.getValue("volunteers").jsonArray.size)
        assertTrue(send(HttpMethod.Get, path, "teamManager").json().getValue("canManage").jsonPrimitive.boolean)

        val check = Check()
        check.expect("no session", send(HttpMethod.Get, path, null), HttpStatusCode.Unauthorized)
        check.expect("no member record", send(HttpMethod.Get, path, "stranger"), HttpStatusCode.Forbidden)
        check.expect("other team's player", send(HttpMethod.Get, path, "outsider"), HttpStatusCode.Forbidden)
        check.expect("member without team", send(HttpMethod.Get, path, "plain"), HttpStatusCode.Forbidden)
        check.expect("unknown event, member", send(HttpMethod.Get, "/api/v1/events/${UUID.randomUUID()}/shifts", "player"), HttpStatusCode.Forbidden)
        check.expect("unknown event, club-wide", send(HttpMethod.Get, "/api/v1/events/${UUID.randomUUID()}/shifts", "clubManager"), HttpStatusCode.NotFound)
        check.done()
        sql { it.execute("DELETE FROM team_events WHERE id = ?", event) }
    }

    @Test
    fun signingUpRespectsWhoMayActCapacityAndClosing() = app {
        val event = event()
        val id = create(event, shift(needed = 1))
        val player = memberOf.getValue("player")
        val check = Check()
        check.expect("no CSRF", send(HttpMethod.Put, volunteer(id, player), "player", withCsrf = null), HttpStatusCode.Forbidden)
        check.expect("for someone else", send(HttpMethod.Put, volunteer(id, memberOf.getValue("second")), "player"), HttpStatusCode.Forbidden)
        check.expect("not in the team", send(HttpMethod.Put, volunteer(id, memberOf.getValue("outsider")), "outsider"), HttpStatusCode.Forbidden)
        check.expect("manager for a player", send(HttpMethod.Put, volunteer(id, player), "teamManager"), HttpStatusCode.Forbidden)
        check.expect("unknown shift", send(HttpMethod.Put, volunteer(UUID.randomUUID().toString(), player), "player"), HttpStatusCode.Forbidden)
        check.expect("bad member id", send(HttpMethod.Put, "/api/v1/shifts/$id/volunteers/nope", "player"), HttpStatusCode.BadRequest)
        check.done()
        assertEquals(0, count("SELECT COUNT(*) FROM shift_volunteers WHERE shift_id = ?::uuid", id))

        // Two people for the last place at once: exactly one gets it.
        val results = coroutineScope {
            listOf("player" to player, "second" to memberOf.getValue("second")).map { (handle, member) ->
                async { send(HttpMethod.Put, volunteer(id, member), handle) }
            }.awaitAll()
        }
        assertEquals(setOf(HttpStatusCode.Conflict, HttpStatusCode.NoContent), results.map { it.status }.toSet())
        assertTrue("\"full\"" in results.single { it.status == HttpStatusCode.Conflict }.bodyAsText())
        assertEquals(1, count("SELECT COUNT(*) FROM shift_volunteers WHERE shift_id = ?::uuid", id))
        val taker = if (results[0].status == HttpStatusCode.NoContent) "player" else "second"
        check.expect("signing up again", send(HttpMethod.Put, volunteer(id, memberOf.getValue(taker)), taker), HttpStatusCode.NoContent)
        check.expect("withdraw", send(HttpMethod.Delete, volunteer(id, memberOf.getValue(taker)), taker), HttpStatusCode.NoContent)
        check.expect("withdraw again", send(HttpMethod.Delete, volunteer(id, memberOf.getValue(taker)), taker), HttpStatusCode.NoContent)
        check.expect("guardian for the child", send(HttpMethod.Put, volunteer(id, kid), "guardian"), HttpStatusCode.NoContent)
        check.done()

        // Once the shift has started nobody signs up or withdraws.
        sql { it.execute("UPDATE event_shifts SET starts_at = now() - INTERVAL '1 minute', ends_at = now() + INTERVAL '1 hour', needed = 5 WHERE id = ?::uuid", id) }
        check.expect("sign up after start", send(HttpMethod.Put, volunteer(id, player), "player"), HttpStatusCode.Conflict)
        check.expect("withdraw after start", send(HttpMethod.Delete, volunteer(id, kid), "guardian"), HttpStatusCode.Conflict)
        check.done()
        // A cancelled event closes its shifts too.
        val later = create(event)
        sql { it.execute("UPDATE team_events SET cancelled = TRUE WHERE id = ?", event) }
        check.expect("cancelled event", send(HttpMethod.Put, volunteer(later, player), "player"), HttpStatusCode.Conflict)
        check.done()
        sql { it.execute("DELETE FROM team_events WHERE id = ?", event) }
    }

    @Test
    fun leavingTheTeamEndsTheSignUps() = app {
        val event = event()
        val id = create(event)
        val second = memberOf.getValue("second")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, volunteer(id, second), "second").status)
        assertEquals(1, count("SELECT COUNT(*) FROM shift_volunteers WHERE shift_id = ?::uuid", id))
        sql { it.execute("DELETE FROM member_team_assignments WHERE team_id = ? AND member_id = ?", teamA, second) }
        assertEquals(0, count("SELECT COUNT(*) FROM shift_volunteers WHERE shift_id = ?::uuid", id), "leaving the team ends the sign-ups")
        sql { it.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamA, second) }
        sql { it.execute("DELETE FROM team_events WHERE id = ?", event) }
    }
}
