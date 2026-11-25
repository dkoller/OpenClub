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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
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

/** Trainings and events (OpenAPI 1.10.0): who manages, who sees, and the order of answers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamEventHttpTest {
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
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Event HTTP Club', ?)", club, "event-http-$club")
            tx.execute("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)",
                teamA, "Event test A ${teamA.toString().take(8)}", teamB, "Event test B ${teamB.toString().take(8)}")
            for (handle in listOf("clubManager", "teamManager", "viewer", "player", "guardian", "plain", "stranger")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "event-$account")
                if (handle == "stranger") continue
                memberOf[handle] = UUID.randomUUID().also {
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                           VALUES (?, ?, ?, 'Events', DATE '1980-01-01', 'e@example.invalid', '+41000000020')""", it, account, handle)
                }
            }
            tx.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'MANAGE_TEAMS')", memberOf.getValue("clubManager"))
            tx.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')", teamA, memberOf.getValue("teamManager"))
            tx.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'VIEW_TEAMS')", teamA, memberOf.getValue("viewer"))
            tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamA, memberOf.getValue("player"))
            tx.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Events', DATE '2016-02-03')", kid)
            tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamB, kid)
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

    private val start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withHour(17).withMinute(0).withSecond(0).withNano(0)
    private fun event(title: String, from: OffsetDateTime = start, extra: String = "") =
        """{"kind":"TRAINING","title":"$title","startsAt":"$from","endsAt":"${from.plusMinutes(90)}","location":" Hall 2 "$extra}"""
    private fun teamEvents(team: UUID) = "/api/v1/teams/$team/events"

    private suspend fun ApplicationTestBuilder.create(team: UUID, handle: String, body: String): List<String> {
        val response = send(HttpMethod.Post, teamEvents(team), handle, body)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json().getValue("ids").jsonArray.map { it.jsonPrimitive.content }
    }

    @Test
    fun managersCreateEditCancelAndDeleteOnlyTheirTeamsEvents() = app {
        // Test order is not fixed: start from empty schedules.
        sql { it.execute("DELETE FROM team_events WHERE team_id IN (?, ?)", teamA, teamB) }
        val check = Check()
        // Session, Origin/CSRF, a MANAGE_TEAMS grant somewhere, then the input, then the team itself.
        check.expect("no session", send(HttpMethod.Post, teamEvents(teamA), null, event("x")), HttpStatusCode.Unauthorized)
        check.expect("wrong origin", send(HttpMethod.Post, teamEvents(teamA), "teamManager", event("x"), withOrigin = "https://evil.example.invalid"), HttpStatusCode.Forbidden)
        check.expect("no CSRF", send(HttpMethod.Post, teamEvents(teamA), "teamManager", event("x"), withCsrf = null), HttpStatusCode.Forbidden)
        check.expect("viewer, bad body", send(HttpMethod.Post, teamEvents(teamA), "viewer", "[]"), HttpStatusCode.Forbidden)
        check.expect("player, bad body", send(HttpMethod.Post, teamEvents(teamA), "player", "[]"), HttpStatusCode.Forbidden)
        check.expect("bad team id", send(HttpMethod.Post, "/api/v1/teams/nope/events", "teamManager", event("x")), HttpStatusCode.BadRequest)
        for ((label, body) in listOf(
            "unknown key" to event("x", extra = ""","teamId":"$teamB""""),
            "bad kind" to event("x").replace("TRAINING", "PARTY"),
            "blank title" to event("   "),
            "end before start" to """{"kind":"MATCH","title":"x","startsAt":"$start","endsAt":"${start.minusMinutes(1)}"}""",
            "longer than 7 days" to """{"kind":"MATCH","title":"x","startsAt":"$start","endsAt":"${start.plusDays(8)}"}""",
            "no offset" to """{"kind":"MATCH","title":"x","startsAt":"2030-01-01T10:00","endsAt":"2030-01-01T11:00"}""",
            "repeat without zone" to event("x", extra = ""","repeatWeeklyUntil":"${start.toLocalDate().plusWeeks(2)}""""),
            "unknown zone" to event("x", extra = ""","repeatWeeklyUntil":"${start.toLocalDate().plusWeeks(2)}","timeZone":"Mars/Base""""),
            "53 copies" to event("x", extra = ""","repeatWeeklyUntil":"${start.toLocalDate().plusWeeks(52)}","timeZone":"Europe/Zurich""""),
            "repeat ends before start" to event("x", extra = ""","repeatWeeklyUntil":"${start.toLocalDate().minusDays(1)}","timeZone":"Europe/Zurich""""),
        )) check.expect(label, send(HttpMethod.Post, teamEvents(teamA), "teamManager", body), HttpStatusCode.BadRequest)
        check.expect("other team", send(HttpMethod.Post, teamEvents(teamB), "teamManager", event("x")), HttpStatusCode.Forbidden)
        check.expect("unknown team, team-scoped", send(HttpMethod.Post, teamEvents(UUID.randomUUID()), "teamManager", event("x")), HttpStatusCode.Forbidden)
        check.expect("unknown team, club-wide", send(HttpMethod.Post, teamEvents(UUID.randomUUID()), "clubManager", event("x")), HttpStatusCode.NotFound)
        check.done()
        assertEquals(0, count("SELECT COUNT(*) FROM team_events WHERE team_id IN (?, ?)", teamA, teamB))

        // Weekly copies up to and including the end date, trimmed text stored.
        val ids = create(teamA, "teamManager", event("Training", extra = ""","notes":"Bring\nshoes","repeatWeeklyUntil":"${start.toLocalDate().plusWeeks(3)}","timeZone":"Europe/Zurich""""))
        assertEquals(4, ids.size)
        assertEquals(listOf("Hall 2"), sql { it.fetch("SELECT DISTINCT location FROM team_events WHERE team_id = ?", teamA).map { r -> r.get(0) } })

        val first = ids.first()
        val edited = """{"kind":"MATCH","title":"Friendly","startsAt":"$start","endsAt":"${start.plusHours(2)}","cancelled":true}"""
        val check2 = Check()
        check2.expect("cancelled as string", send(HttpMethod.Put, "/api/v1/events/$first", "teamManager", edited.replace("true}", "\"true\"}")), HttpStatusCode.BadRequest)
        check2.expect("no cancelled", send(HttpMethod.Put, "/api/v1/events/$first", "teamManager", event("x")), HttpStatusCode.BadRequest)
        check2.expect("viewer edits", send(HttpMethod.Put, "/api/v1/events/$first", "viewer", edited), HttpStatusCode.Forbidden)
        // A team-scoped manager cannot tell an unknown event from another team's (403); a club-wide one gets 404.
        check2.expect("unknown event, team-scoped", send(HttpMethod.Put, "/api/v1/events/${UUID.randomUUID()}", "teamManager", edited), HttpStatusCode.Forbidden)
        check2.expect("unknown event, club-wide", send(HttpMethod.Put, "/api/v1/events/${UUID.randomUUID()}", "clubManager", edited), HttpStatusCode.NotFound)
        check2.expect("edit", send(HttpMethod.Put, "/api/v1/events/$first", "teamManager", edited), HttpStatusCode.NoContent)
        check2.done()
        val row = sql { it.fetchOne("SELECT kind, title, cancelled, location FROM team_events WHERE id = ?::uuid", first)!! }
        assertEquals(listOf("MATCH", "Friendly", true, null), row.intoList())

        // A team-B event is out of reach for team A's manager, but not for the club-wide manager.
        val other = create(teamB, "clubManager", event("B training")).single()
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, "/api/v1/events/$other", "teamManager", edited).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Delete, "/api/v1/events/$other", "teamManager").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/events/${ids.last()}", "teamManager").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Delete, "/api/v1/events/${ids.last()}", "teamManager").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Delete, "/api/v1/events/${ids.last()}", "clubManager").status)
        assertEquals(3, count("SELECT COUNT(*) FROM team_events WHERE team_id = ?", teamA))

        // Revoking the grant takes effect at once.
        sql { it.execute("DELETE FROM team_capability_grants WHERE member_id = ? AND capability = 'MANAGE_TEAMS'", memberOf.getValue("teamManager")) }
        try {
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Delete, "/api/v1/events/$first", "teamManager").status)
        } finally {
            sql { it.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')", teamA, memberOf.getValue("teamManager")) }
        }
    }

    @Test
    fun eachPersonSeesExactlyTheSchedulesTheyMay() = app {
        sql { it.execute("DELETE FROM team_events WHERE team_id IN (?, ?)", teamA, teamB) }
        val a = create(teamA, "clubManager", event("A training")).single()
        val b = create(teamB, "clubManager", event("B training", start.plusDays(1))).single()
        create(teamA, "clubManager", event("Long ago", start.minusDays(30)))

        // A team's schedule: viewers and managers of that team; canManage tells the UI which controls to show.
        val viewer = send(HttpMethod.Get, teamEvents(teamA), "viewer").json()
        assertEquals(listOf(a), viewer.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(false, viewer.getValue("canManage").jsonPrimitive.content.toBoolean())
        assertEquals(true, send(HttpMethod.Get, teamEvents(teamA), "teamManager").json().getValue("canManage").jsonPrimitive.content.toBoolean())
        val item = viewer.getValue("items").jsonArray.single().jsonObject
        assertEquals(setOf("id", "teamId", "teamName", "kind", "title", "startsAt", "endsAt", "location", "notes", "cancelled", "counts", "venueId", "venueName"), item.keys)
        assertTrue(item.getValue("startsAt").jsonPrimitive.content.endsWith("Z"))
        // An explicit range includes the past event.
        val past = send(HttpMethod.Get, teamEvents(teamA) + "?from=" + start.minusDays(40).toString().replace("+", "%2B") + "&to=" + start.plusDays(1), "viewer")
        assertEquals(2, past.json().getValue("items").jsonArray.size)

        val check = Check()
        check.expect("no session", send(HttpMethod.Get, teamEvents(teamA), null), HttpStatusCode.Unauthorized)
        check.expect("player without grant", send(HttpMethod.Get, teamEvents(teamA), "player"), HttpStatusCode.Forbidden)
        check.expect("viewer of A reads B", send(HttpMethod.Get, teamEvents(teamB), "viewer"), HttpStatusCode.Forbidden)
        check.expect("unknown team, club-wide", send(HttpMethod.Get, teamEvents(UUID.randomUUID()), "clubManager"), HttpStatusCode.NotFound)
        check.expect("bad range", send(HttpMethod.Get, teamEvents(teamA) + "?from=yesterday", "viewer"), HttpStatusCode.BadRequest)
        check.expect("range over a year", send(HttpMethod.Get, teamEvents(teamA) + "?to=" + start.plusDays(400).toString().replace("+", "%2B"), "viewer"), HttpStatusCode.BadRequest)
        check.expect("unknown parameter", send(HttpMethod.Get, teamEvents(teamA) + "?team=all", "viewer"), HttpStatusCode.BadRequest)
        check.expect("mine without member record", send(HttpMethod.Get, "/api/v1/me/events", "stranger"), HttpStatusCode.Forbidden)
        check.expect("mine without session", send(HttpMethod.Get, "/api/v1/me/events", null), HttpStatusCode.Unauthorized)
        check.done()

        // My events: the player sees team A's, the guardian team B's for the child, the others nothing.
        suspend fun mine(handle: String) = send(HttpMethod.Get, "/api/v1/me/events", handle).json().getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf(a), mine("player").map { it.getValue("id").jsonPrimitive.content })
        val forChild = mine("guardian").single()
        assertEquals(b, forChild.getValue("id").jsonPrimitive.content)
        assertEquals(listOf(kid.toString() to "Kiddo"), forChild.getValue("for").jsonArray.map {
            it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("firstName").jsonPrimitive.content })
        assertEquals(emptyList<JsonObject>(), mine("plain"))
        assertEquals(emptyList<JsonObject>(), mine("viewer"), "a team grant alone does not put events in one's own schedule")

        // Once the child comes of age the guardian no longer sees the child's events.
        sql { it.execute("UPDATE members SET date_of_birth = DATE '1990-01-01', email = 'k@example.invalid', phone = '+41000000021' WHERE id = ?", kid) }
        try {
            assertEquals(emptyList<JsonObject>(), mine("guardian"))
        } finally {
            sql { it.execute("UPDATE members SET date_of_birth = DATE '2016-02-03' WHERE id = ?", kid) }
        }
    }

    private fun attendance(event: String, member: UUID) = "/api/v1/events/$event/attendance/${member}"
    private fun answer(response: String) = """{"response":"$response"}"""

    @Test
    fun membersAndGuardiansAnswerUntilTheStartAndTeamViewersSeeTheAnswers() = app {
        // Test order is not fixed: the visibility test may have ended the guardianship (the child came of age).
        sql {
            it.execute("DELETE FROM team_events WHERE team_id IN (?, ?)", teamA, teamB)
            it.execute("""INSERT INTO member_guardianships (minor_member_id, guardian_member_id)
                          SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM member_guardianships WHERE minor_member_id = ?)""",
                kid, memberOf.getValue("guardian"), kid)
        }
        val player = memberOf.getValue("player")
        val a = create(teamA, "clubManager", event("A training")).single()
        val b = create(teamB, "clubManager", event("B training")).single()
        val past = create(teamA, "clubManager", event("Past", start.minusDays(30))).single()
        val cancelled = create(teamA, "clubManager", event("Cancelled", start.plusDays(1))).single()
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/events/$cancelled", "clubManager",
            """{"kind":"TRAINING","title":"Cancelled","startsAt":"${start.plusDays(1)}","endsAt":"${start.plusDays(1).plusHours(1)}","cancelled":true}""").status)

        val check = Check()
        check.expect("no session", send(HttpMethod.Put, attendance(a, player), null, answer("YES")), HttpStatusCode.Unauthorized)
        check.expect("wrong origin", send(HttpMethod.Put, attendance(a, player), "player", answer("YES"), withOrigin = "https://evil.example.invalid"), HttpStatusCode.Forbidden)
        check.expect("no member record", send(HttpMethod.Put, "/api/v1/events/x/attendance/y", "stranger", "[]"), HttpStatusCode.Forbidden)
        check.expect("bad ids", send(HttpMethod.Put, "/api/v1/events/x/attendance/y", "player", answer("YES")), HttpStatusCode.BadRequest)
        check.expect("lower case", send(HttpMethod.Put, attendance(a, player), "player", answer("yes")), HttpStatusCode.BadRequest)
        check.expect("reason field", send(HttpMethod.Put, attendance(a, player), "player", """{"response":"NO","reason":"ill"}"""), HttpStatusCode.BadRequest)
        check.expect("someone else's child", send(HttpMethod.Put, attendance(b, kid), "player", answer("YES")), HttpStatusCode.Forbidden)
        check.expect("another team's event", send(HttpMethod.Put, attendance(b, player), "player", answer("YES")), HttpStatusCode.Forbidden)
        check.expect("unknown event", send(HttpMethod.Put, attendance(UUID.randomUUID().toString(), player), "player", answer("YES")), HttpStatusCode.Forbidden)
        check.expect("guardian for self, not in team", send(HttpMethod.Put, attendance(b, memberOf.getValue("guardian")), "guardian", answer("YES")), HttpStatusCode.Forbidden)
        check.expect("a manager for a player", send(HttpMethod.Put, attendance(a, player), "teamManager", answer("YES")), HttpStatusCode.Forbidden)
        check.expect("started", send(HttpMethod.Put, attendance(past, player), "player", answer("YES")), HttpStatusCode.Conflict)
        check.expect("cancelled", send(HttpMethod.Put, attendance(cancelled, player), "player", answer("YES")), HttpStatusCode.Conflict)
        check.expect("clear after start", send(HttpMethod.Delete, attendance(past, player), "player"), HttpStatusCode.Conflict)
        check.expect("own answer", send(HttpMethod.Put, attendance(a, player), "player", answer("YES")), HttpStatusCode.NoContent)
        check.expect("changed answer", send(HttpMethod.Put, attendance(a, player), "player", answer("MAYBE")), HttpStatusCode.NoContent)
        check.expect("guardian for child", send(HttpMethod.Put, attendance(b, kid), "guardian", answer("NO")), HttpStatusCode.NoContent)
        check.done()
        assertEquals(2, count("SELECT COUNT(*) FROM event_attendance WHERE event_id IN (?::uuid, ?::uuid)", a, b))

        // Own schedules carry one's own (or the child's) answer and the totals, never other people's answers.
        suspend fun mine(handle: String) = send(HttpMethod.Get, "/api/v1/me/events", handle).json().getValue("items").jsonArray.map { it.jsonObject }
        val playerItem = mine("player").single { it.getValue("id").jsonPrimitive.content == a }
        assertEquals("MAYBE", playerItem.getValue("for").jsonArray.single().jsonObject.getValue("response").jsonPrimitive.content)
        assertEquals("""{"yes":0,"no":0,"maybe":1}""", playerItem.getValue("counts").toString())
        val childItem = mine("guardian").single()
        assertEquals("NO", childItem.getValue("for").jsonArray.single().jsonObject.getValue("response").jsonPrimitive.content)

        // Viewers and managers of the team see every answer by public display name only.
        val list = send(HttpMethod.Get, "/api/v1/events/$a/attendance", "viewer")
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals("no-store", list.headers[HttpHeaders.CacheControl])
        val items = list.json().getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("player Events" to "MAYBE"), items.map { it.getValue("displayName").jsonPrimitive.content to it.getValue("response").jsonPrimitive.content })
        assertEquals(setOf("displayName", "response"), items.single().keys)
        val minors = send(HttpMethod.Get, "/api/v1/events/$b/attendance", "clubManager").json().getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Kiddo E."), minors.map { it.getValue("displayName").jsonPrimitive.content })
        val check2 = Check()
        check2.expect("list without session", send(HttpMethod.Get, "/api/v1/events/$a/attendance", null), HttpStatusCode.Unauthorized)
        check2.expect("list without team grant", send(HttpMethod.Get, "/api/v1/events/$a/attendance", "player"), HttpStatusCode.Forbidden)
        check2.expect("list of another team", send(HttpMethod.Get, "/api/v1/events/$b/attendance", "viewer"), HttpStatusCode.Forbidden)
        check2.expect("list bad id", send(HttpMethod.Get, "/api/v1/events/x/attendance", "viewer"), HttpStatusCode.BadRequest)
        check2.expect("list unknown event", send(HttpMethod.Get, "/api/v1/events/${UUID.randomUUID()}/attendance", "clubManager"), HttpStatusCode.NotFound)
        // Clearing works twice; leaving the team removes the remaining answer.
        check2.expect("clear", send(HttpMethod.Delete, attendance(a, player), "player"), HttpStatusCode.NoContent)
        check2.expect("clear again", send(HttpMethod.Delete, attendance(a, player), "player"), HttpStatusCode.NoContent)
        check2.done()
        assertEquals(JsonNull, send(HttpMethod.Get, "/api/v1/events/$a/attendance", "viewer").json()
            .getValue("items").jsonArray.single().jsonObject.getValue("response"))
        sql { it.execute("DELETE FROM member_team_assignments WHERE team_id = ? AND member_id = ?", teamB, kid) }
        try {
            assertEquals(0, count("SELECT COUNT(*) FROM event_attendance WHERE member_id = ?", kid))
        } finally {
            sql { it.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamB, kid) }
        }
    }

    @Test
    fun viewersAndManagersSeeAttendanceStatisticsForTheirTeamOnly() = app {
        // OpenAPI 1.18.0. A fixed month in the past, so events of the other tests never fall into the range.
        val path = "/api/v1/teams/$teamA/attendance-statistics?from=2025-03-01&to=2025-03-31"
        val player = memberOf.getValue("player")
        val events = listOf("2025-03-03T17:00:00Z" to false, "2025-03-10T17:00:00Z" to false, "2025-03-17T17:00:00Z" to true,
            "2025-04-01T17:00:00Z" to false).map { (at, cancelled) ->
            UUID.randomUUID().also { sql { db -> db.execute(
                """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, cancelled)
                   VALUES (?, ?, 'TRAINING', 'Stats', ?::timestamptz, ?::timestamptz + INTERVAL '1 hour', ?)""",
                it, teamA, at, at, cancelled) } }
        }
        try {
            sql { db ->
                db.execute("INSERT INTO event_attendance (event_id, member_id, response) VALUES (?, ?, 'YES')", events[0], player)
                db.execute("INSERT INTO event_attendance (event_id, member_id, response) VALUES (?, ?, 'NO')", events[2], player)   // cancelled
                db.execute("INSERT INTO event_attendance (event_id, member_id, response) VALUES (?, ?, 'MAYBE')", events[3], player) // outside
            }
            val body = send(HttpMethod.Get, path, "viewer").json()
            assertEquals(setOf("from", "to", "held", "items", "totals"), body.keys)
            assertEquals(2, body.getValue("held").jsonPrimitive.int, "cancelled events and events outside the range do not count")
            val row = body.getValue("items").jsonArray.single().jsonObject
            assertEquals(setOf("displayName", "yes", "no", "maybe", "noAnswer"), row.keys)
            assertEquals("player Events", row.getValue("displayName").jsonPrimitive.content)
            assertEquals(listOf(1, 0, 0, 1), listOf("yes", "no", "maybe", "noAnswer").map { row.getValue(it).jsonPrimitive.int })
            val totals = body.getValue("totals").jsonObject
            assertEquals(listOf(1, 0, 0, 1), listOf("yes", "no", "maybe", "noAnswer").map { totals.getValue(it).jsonPrimitive.int })
            assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, path, "teamManager").status)
            assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, path, "clubManager").status)
            val defaults = send(HttpMethod.Get, "/api/v1/teams/$teamA/attendance-statistics", "viewer").json()
            assertEquals(java.time.LocalDate.now(ZoneOffset.UTC).toString(), defaults.getValue("to").jsonPrimitive.content)

            val check = Check()
            check.expect("no session", send(HttpMethod.Get, path, null), HttpStatusCode.Unauthorized)
            check.expect("player", send(HttpMethod.Get, path, "player"), HttpStatusCode.Forbidden)
            check.expect("plain member", send(HttpMethod.Get, path, "plain"), HttpStatusCode.Forbidden)
            check.expect("other team", send(HttpMethod.Get, "/api/v1/teams/$teamB/attendance-statistics", "viewer"), HttpStatusCode.Forbidden)
            check.expect("unknown team, team-scoped", send(HttpMethod.Get, "/api/v1/teams/${UUID.randomUUID()}/attendance-statistics", "viewer"), HttpStatusCode.Forbidden)
            check.expect("unknown team, club-wide", send(HttpMethod.Get, "/api/v1/teams/${UUID.randomUUID()}/attendance-statistics", "clubManager"), HttpStatusCode.NotFound)
            for (query in listOf("from=2025-03-31&to=2025-03-01", "from=2024-01-01&to=2025-03-01", "from=2025-02-30", "to=2025-3-1",
                "from=2025-03-01&from=2025-03-02", "other=1", "from=1999-12-31&to=2000-01-01")) {
                check.expect(query, send(HttpMethod.Get, "/api/v1/teams/$teamA/attendance-statistics?$query", "viewer"), HttpStatusCode.BadRequest)
            }
            check.done()
        } finally {
            sql { db -> db.execute("DELETE FROM team_events WHERE id = ANY(?::uuid[])", events.toTypedArray()) }
        }
    }
}
