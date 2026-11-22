package org.openclub.api.notifications

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
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.module
import org.openclub.api.teams.TeamEvents
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Email notifications (OpenAPI 1.19.0): who gets which message, the member's switches, the outbox sending and its
 * cleanup, and the settings routes. No SMTP server: a recording [Mailer] stands in for it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationsTest {
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
    private val notifier = Notifier(requireNotNull(MailConfig.fromEnvironment(mapOf(
        "OPENCLUB_SMTP_HOST" to "mail.example.invalid", "OPENCLUB_SMTP_USERNAME" to "u", "OPENCLUB_SMTP_PASSWORD" to "p",
        "OPENCLUB_SMTP_FROM" to "club@example.invalid"))), origin)
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }
    private fun <T> tx(block: (DSLContext) -> T): T = sql { db -> db.transactionResult { c -> block(DSL.using(c)) } }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { db ->
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Notify Club', ?)", club, "notify-$club")
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)",
                team, "Notify A ${team.toString().take(8)}", otherTeam, "Notify B ${otherTeam.toString().take(8)}")
            // player: in the team, signed in. guardian: the kid's guardian, English. quiet: in the team, every
            // switch off. unlinked: in the team, no sign-in. outsider: other team. manager: manages the team.
            for (handle in listOf("player", "guardian", "quiet", "unlinked", "outsider", "manager", "stranger")) {
                val account = if (handle == "unlinked") null else UUID.randomUUID().also { accounts[handle] = it }
                if (account != null) {
                    db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                                  VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "notify-$account")
                }
                if (handle == "stranger") continue
                memberOf[handle] = UUID.randomUUID().also {
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, ?, ?, 'Notify', DATE '1980-01-01', ?, '+41000000050')""",
                        it, account, handle, "$handle@example.invalid")
                }
            }
            for (handle in listOf("player", "quiet", "unlinked")) db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, memberOf.getValue(handle))
            db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", otherTeam, memberOf.getValue("outsider"))
            db.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Notify', CURRENT_DATE - 3650)", kid)
            db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, kid)
            db.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
            db.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')", team, memberOf.getValue("manager"))
            db.execute("INSERT INTO notification_settings (member_id, language) VALUES (?, 'en')", memberOf.getValue("guardian"))
            db.execute("""INSERT INTO notification_settings (member_id, event_changes, new_events, announcements, shift_reminders, helpers_wanted, answer_reminders)
                          VALUES (?, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE)""", memberOf.getValue("quiet"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM announcements WHERE title LIKE 'Notify%'")
                tx.execute("DELETE FROM teams WHERE id IN (?, ?)", team, otherTeam)
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", (memberOf.values + kid).toTypedArray())
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    @BeforeEach
    fun emptyOutbox() {
        if (database != null) sql { it.execute("DELETE FROM email_outbox WHERE member_id = ANY(?::uuid[])", (memberOf.values + kid).toTypedArray()) }
    }

    /** (handle, kind, subject, body) of every queued message for this class's members, ordered by handle. */
    private fun outbox(): List<List<String>> = sql { db ->
        db.fetch("SELECT member_id, kind, subject, body FROM email_outbox WHERE member_id = ANY(?::uuid[])",
            (memberOf.values + kid).toTypedArray()).map { row ->
            val handle = memberOf.entries.first { it.value == row.get(0, UUID::class.java) }.key
            listOf(handle, row.get(1).toString(), row.get(2).toString(), row.get(3).toString())
        }.sortedBy { it[0] }
    }

    private val start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(4).withHour(16).withMinute(0).withSecond(0).withNano(0)
    private fun input(title: String) = TeamEvents.Input(TeamEvents.Kind.TRAINING, title, start, start.plusMinutes(90), "Hall 3", "Bring water")

    @Test
    fun newEventsReachTheTeamAndGuardiansWhoWantThem() {
        val created = tx { TeamEvents(it, notifier).create(accounts.getValue("manager"), team, input("Notify training"), listOf(start, start.plusWeeks(1))) }
        assertTrue(created is TeamEvents.Created.Done)
        val mails = outbox()
        assertEquals(listOf("guardian", "player"), mails.map { it[0] },
            "not the member who turned it off, not one without a sign-in, not another team, not the manager outside the team")
        assertTrue(mails.all { it[1] == "NEW_EVENTS" })
        val guardian = mails.first { it[0] == "guardian" }
        assertTrue(guardian[2].endsWith("] New: Notify A ${team.toString().take(8)} – Notify training (2)"), guardian[2])
        assertTrue("Hall 3" in guardian[3] && "Bring water" in guardian[3] && origin in guardian[3])
        assertTrue(mails.first { it[0] == "player" }[2].contains("] Neu: "), "German by default")
        assertFalse(mails.any { "Kiddo" in it[3] || "player Notify" in it[3] }, "no members' names")
        tx { db -> TeamEvents(db).create(accounts.getValue("manager"), team, input("Notify silent"), listOf(start)) }
        assertEquals(2, outbox().size, "without a notifier nothing is queued")
        sql { it.execute("DELETE FROM team_events WHERE team_id = ?", team) }
    }

    @Test
    fun changesCancellationsAndDeletionsAreReportedOnce() {
        val id = (tx { TeamEvents(it, notifier).create(accounts.getValue("manager"), team, input("Notify match"), listOf(start)) }
            as TeamEvents.Created.Done).ids.single()
        sql { it.execute("DELETE FROM email_outbox WHERE member_id = ANY(?::uuid[])", memberOf.values.toTypedArray()) }
        fun replace(title: String, cancelled: Boolean) = tx { TeamEvents(it, notifier).replace(accounts.getValue("manager"), id, input(title), cancelled) }
        fun kinds() = outbox().filter { it[0] == "player" }.map { it[1] }

        replace("Notify match", false)
        assertEquals(emptyList<String>(), kinds(), "saving without a change sends nothing")
        replace("Notify match moved", false)
        assertEquals(listOf("EVENT_CHANGED"), kinds())
        replace("Notify match moved", true)
        replace("Notify match moved again", true)
        assertEquals(listOf("EVENT_CANCELLED", "EVENT_CHANGED"), kinds().sorted(), "editing a cancelled event stays silent")
        assertEquals(TeamEvents.Change.DENIED, tx { TeamEvents(it, notifier).replace(accounts.getValue("player"), id, input("x"), false) })
        assertEquals(2, kinds().size, "a refused change queues nothing")
        tx { TeamEvents(it, notifier).delete(accounts.getValue("manager"), id) }
        assertEquals(2, kinds().size, "deleting an already cancelled event stays silent")

        val other = (tx { TeamEvents(it, notifier).create(accounts.getValue("manager"), team, input("Notify friendly"), listOf(start)) }
            as TeamEvents.Created.Done).ids.single()
        sql { it.execute("DELETE FROM email_outbox WHERE member_id = ANY(?::uuid[])", memberOf.values.toTypedArray()) }
        tx { TeamEvents(it, notifier).delete(accounts.getValue("manager"), other) }
        val cancelled = outbox().first { it[0] == "guardian" }
        assertEquals("EVENT_CANCELLED", cancelled[1])
        assertTrue("This event is cancelled." in cancelled[3] && "Notify friendly" in cancelled[2])
    }

    @Test
    fun clubWideAnnouncementsReachEveryoneWhoWantsThem() {
        val id = UUID.randomUUID()
        tx { db ->
            db.execute("INSERT INTO announcements (id, title, body, show_until) VALUES (?, 'Notify assembly', 'Friday 20:00', CURRENT_DATE + 10)", id)
            notifier.announcementPosted(db, id)
        }
        assertEquals(listOf("guardian", "manager", "outsider", "player"), outbox().map { it[0] })
        assertTrue(outbox().all { it[1] == "ANNOUNCEMENT" && "Friday 20:00" in it[3] })
    }

    @Test
    fun shiftRemindersGoOnceToTheVolunteerOrTheChildsGuardian() {
        val event = UUID.randomUUID()
        val shift = UUID.randomUUID()
        val soon = OffsetDateTime.now(ZoneOffset.UTC).plusHours(3).withNano(0)
        sql { db ->
            db.execute("""INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at)
                          VALUES (?, ?, 'MATCH', 'Notify cup', ?::timestamptz, ?::timestamptz)""", event, team, soon.toString(), soon.plusHours(3).toString())
            db.execute("""INSERT INTO event_shifts (id, event_id, title, starts_at, ends_at, needed)
                          VALUES (?, ?, 'Bar', ?::timestamptz, ?::timestamptz, 5)""", shift, event, soon.toString(), soon.plusHours(1).toString())
            for (member in listOf(kid, memberOf.getValue("player"), memberOf.getValue("quiet")))
                db.execute("INSERT INTO shift_volunteers (shift_id, member_id) VALUES (?, ?)", shift, member)
        }
        try {
            assertTrue(tx { notifier.queueShiftReminders(it) } >= 3)
            val mails = outbox()
            assertEquals(listOf("guardian", "player"), mails.map { it[0] }, "the kid's guardian and the player; quiet turned reminders off")
            assertTrue(mails.first { it[0] == "guardian" }[3].startsWith("For: Kiddo\n"), "a guardian learns whom it is for")
            assertFalse("For:" in mails.first { it[0] == "player" }[3] || "Für:" in mails.first { it[0] == "player" }[3])
            tx { notifier.queueShiftReminders(it) }
            assertEquals(2, outbox().size, "once per sign-up")
        } finally {
            sql { it.execute("DELETE FROM team_events WHERE id = ?", event) }
        }
    }

    @Test
    fun helpersWantedGoesOncePerShiftToTheTeamButNotToThoseAlreadyHelping() {
        val event = UUID.randomUUID()
        val inSix = OffsetDateTime.now(ZoneOffset.UTC).plusDays(6).withNano(0)
        val shifts = (0 until 5).map { UUID.randomUUID() }
        val (bar, grill, full, offShift, later) = shifts
        val farEvent = UUID.randomUUID()
        val cancelledEvent = UUID.randomUUID()
        sql { db ->
            fun event(id: UUID, at: OffsetDateTime, title: String, cancelled: Boolean = false) = db.execute(
                """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, location, cancelled)
                   VALUES (?, ?, 'MATCH', ?, ?::timestamptz, ?::timestamptz, 'Hall 3', ?)""",
                id, team, title, at.toString(), at.plusHours(4).toString(), cancelled)
            fun shift(id: UUID, eventId: UUID, title: String, at: OffsetDateTime, needed: Int) = db.execute(
                """INSERT INTO event_shifts (id, event_id, title, starts_at, ends_at, needed)
                   VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, ?)""", id, eventId, title, at.toString(), at.plusHours(2).toString(), needed)
            event(event, inSix, "Notify tournament")
            shift(bar, event, "Bar", inSix, 3)
            shift(grill, event, "Grill", inSix.plusHours(2), 2)
            shift(full, event, "Kasse", inSix, 1)
            event(farEvent, inSix.plusDays(5), "Notify later")
            shift(later, farEvent, "Bar", inSix.plusDays(5), 2)
            event(cancelledEvent, inSix, "Notify off", cancelled = true)
            shift(offShift, cancelledEvent, "Bar", inSix, 2)
            // The kid helps at the full till, so the guardian is not asked again; the player is not helping yet.
            db.execute("INSERT INTO shift_volunteers (shift_id, member_id) VALUES (?, ?)", full, kid)
            db.execute("INSERT INTO shift_volunteers (shift_id, member_id) VALUES (?, ?)", bar, memberOf.getValue("unlinked"))
        }
        try {
            // Other classes may leave due shifts of their own teams; only this class's shifts and members are checked.
            assertTrue(tx { notifier.queueHelperCalls(it) } >= 2)
            val called = sql { db -> db.fetch("SELECT shift_id FROM helper_calls_sent WHERE shift_id = ANY(?::uuid[])", shifts.toTypedArray())
                .map { it.get(0, UUID::class.java) }.toSet() }
            assertEquals(setOf(bar, grill), called, "not the full one, the one in 11 days or the cancelled one")
            val mails = outbox()
            assertEquals(listOf("player"), mails.map { it[0] },
                "one mail per event; not the guardian whose kid helps, not quiet (switched off), not without a sign-in")
            val (_, kind, subject, body) = mails.single()
            assertEquals("HELPERS_WANTED", kind)
            assertTrue(subject.endsWith("] Helfer gesucht: Notify A ${team.toString().take(8)} – Notify tournament"), subject)
            assertTrue("Bar, " in body && ": 2 von 3 Plätzen frei" in body, body)
            assertTrue("Grill, " in body && ": 2 von 2 Plätzen frei" in body, body)
            assertFalse("Kasse" in body, "full shifts are not listed")
            assertTrue("$origin/#/schedule/$event" in body && "Hall 3" in body)
            assertFalse("Kiddo" in body || "unlinked" in body, "never who signed up")
            tx { notifier.queueHelperCalls(it) }
            assertEquals(1, outbox().size, "each shift is called once")
            assertEquals(0, tx { Notifier.DISABLED.queueHelperCalls(it) })
        } finally {
            sql { it.execute("DELETE FROM team_events WHERE id IN (?, ?, ?)", event, farEvent, cancelledEvent) }
        }
        assertEquals(0, (sql { it.fetchValue("SELECT count(*) FROM helper_calls_sent WHERE shift_id = ANY(?::uuid[])", shifts.toTypedArray()) } as Number).toInt(),
            "the record goes with the shift")
    }

    private fun teamEvent(title: String, at: OffsetDateTime, cancelled: Boolean = false): UUID = UUID.randomUUID().also {
        sql { db -> db.execute(
            """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, location, cancelled)
               VALUES (?, ?, 'TRAINING', ?, ?::timestamptz, ?::timestamptz, 'Hall 3', ?)""",
            it, team, title, at.toString(), at.plusHours(2).toString(), cancelled) }
    }

    @Test
    fun automaticAnswerRemindersGoOnceTwoDaysAheadToThoseWithoutAnswer() {
        val now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0)
        val due = teamEvent("Notify due", now.plusHours(36))
        val early = teamEvent("Notify early", now.plusDays(3))
        val tooClose = teamEvent("Notify tomorrow", now.plusHours(20))
        val off = teamEvent("Notify off", now.plusHours(36), cancelled = true)
        sql { it.execute("INSERT INTO event_attendance (event_id, member_id, response) VALUES (?, ?, 'YES')", due, memberOf.getValue("player")) }
        try {
            assertTrue(tx { notifier.queueAnswerReminders(it) } >= 1)
            val runs = sql { db -> db.fetch("SELECT event_id FROM answer_reminders WHERE automatic AND event_id = ANY(?::uuid[])",
                arrayOf(due, early, tooClose, off)).map { it.get(0, UUID::class.java) } }
            assertEquals(listOf(due), runs, "only the event between one and two days ahead, not cancelled")
            val mails = outbox()
            assertEquals(listOf("guardian"), mails.map { it[0] },
                "not the player who answered, not quiet (switched off), not without a sign-in; the kid's guardian")
            val (_, kind, subject, body) = mails.single()
            assertEquals("ANSWER_REMINDER", kind)
            assertTrue(subject.endsWith("] Answer missing: Notify A ${team.toString().take(8)} – Notify due"), subject)
            assertTrue(body.startsWith("For: Kiddo\n") && "Please answer" in body && "$origin/#/schedule/$due" in body, body)
            tx { notifier.queueAnswerReminders(it) }
            assertEquals(1, outbox().size, "once per event")
            assertEquals(0, tx { Notifier.DISABLED.queueAnswerReminders(it) })
        } finally {
            sql { it.execute("DELETE FROM team_events WHERE id IN (?, ?, ?, ?)", due, early, tooClose, off) }
        }
    }

    @Test
    fun coachesRemindThoseWithoutAnswerAtMostEveryTwelveHours() {
        val now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0)
        val event = teamEvent("Notify ask", now.plusDays(5))
        val started = teamEvent("Notify running", now.minusMinutes(30))
        val other = UUID.randomUUID()
        sql { db ->
            db.execute("""INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at)
                          VALUES (?, ?, 'TRAINING', 'Notify other', ?::timestamptz, ?::timestamptz)""",
                other, otherTeam, now.plusDays(5).toString(), now.plusDays(5).plusHours(1).toString())
            db.execute("INSERT INTO event_attendance (event_id, member_id, response) VALUES (?, ?, 'NO')", event, memberOf.getValue("player"))
        }
        suspend fun ApplicationTestBuilder.remind(id: Any, handle: String?, withCsrf: String? = csrf): HttpResponse =
            client.request("/api/v1/events/$id/attendance-reminders") {
                method = HttpMethod.Post
                if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
                header(HttpHeaders.Origin, origin)
                if (withCsrf != null) header("X-CSRF-Token", withCsrf)
            }
        try {
            app {
                val failures = mutableListOf<String>()
                fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
                    if (response.status != status) failures += "$label: expected $status but was ${response.status}"
                }
                expect("no session", remind(event, null), HttpStatusCode.Unauthorized)
                expect("no CSRF", remind(event, "manager", withCsrf = null), HttpStatusCode.Forbidden)
                expect("player", remind(event, "player"), HttpStatusCode.Forbidden)
                expect("guardian", remind(event, "guardian"), HttpStatusCode.Forbidden)
                expect("bad id", remind("nope", "manager"), HttpStatusCode.BadRequest)
                expect("other team", remind(other, "manager"), HttpStatusCode.Forbidden)
                expect("unknown, team-scoped", remind(UUID.randomUUID(), "manager"), HttpStatusCode.Forbidden)
                expect("started", remind(started, "manager"), HttpStatusCode.Conflict)
                assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
                assertEquals(0, outbox().size, "nothing queued by refused requests")

                val first = remind(event, "manager")
                assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
                assertEquals("no-store", first.headers[HttpHeaders.CacheControl])
                assertEquals(Json.parseToJsonElement("""{"reminded":3}"""), Json.parseToJsonElement(first.bodyAsText()),
                    "quiet, unlinked and the kid have not answered")
                assertEquals(listOf("guardian"), outbox().map { it[0] })
                val again = remind(event, "manager")
                assertEquals(HttpStatusCode.Conflict, again.status)
                assertTrue("recent" in again.bodyAsText())
                assertEquals(1, outbox().size, "at most every 12 hours")
            }
            testApplication {
                application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database)) }
                val response = client.request("/api/v1/events/$event/attendance-reminders") {
                    method = HttpMethod.Post
                    header(HttpHeaders.Cookie, "${cookies.sessionName}=manager")
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertTrue("email_off" in response.bodyAsText(), "without a mail configuration nothing is sent")
            }
        } finally {
            sql { it.execute("DELETE FROM team_events WHERE id IN (?, ?, ?)", event, started, other) }
        }
    }

    @Test
    fun theOutboxSendsRetriesSkipsAndCleansUp() {
        val sent = mutableListOf<Pair<String, String>>()
        val player = memberOf.getValue("player")
        val outsider = memberOf.getValue("outsider")
        sql { db ->
            db.execute("INSERT INTO email_outbox (id, member_id, kind, subject, body) VALUES (gen_random_uuid(), ?, 'ANNOUNCEMENT', 'Notify one', 'Body')", player)
            db.execute("INSERT INTO email_outbox (id, member_id, kind, subject, body) VALUES (gen_random_uuid(), ?, 'ANNOUNCEMENT', 'Notify two', 'Body')", outsider)
        }
        // The outsider loses their sign-in: their message is dropped, not sent.
        val identities = sql { it.fetch("DELETE FROM account_identities WHERE account_id = ? RETURNING id, issuer, subject", accounts.getValue("outsider")) }
        try {
            val outcome = sql { MailWorker.sendBatch(it, Mailer { to, subject, _ -> sent += to to subject }, limit = 1000) }
            assertTrue(("player@example.invalid" to "Notify one") in sent)
            assertFalse(sent.any { it.first == "outsider@example.invalid" })
            assertTrue(outcome.sent >= 1 && outcome.skipped >= 1)
        } finally {
            sql { db -> identities.forEach {
                db.execute("INSERT INTO account_identities (id, account_id, issuer, subject) VALUES (?, ?, ?, ?)",
                    it.get(0), accounts.getValue("outsider"), it.get(1), it.get(2))
            } }
        }
        sql { it.execute("INSERT INTO email_outbox (id, member_id, kind, subject, body) VALUES (gen_random_uuid(), ?, 'ANNOUNCEMENT', 'Notify three', 'Body')", player) }
        sql { MailWorker.sendBatch(it, Mailer { _, _, _ -> throw IllegalStateException("SMTP down") }, limit = 1000) }
        val retry = sql { it.fetchOne("SELECT attempts, next_attempt_at > now() FROM email_outbox WHERE subject = 'Notify three' AND member_id = ?", player) }!!
        assertEquals(1, retry.get(0, Int::class.java))
        assertTrue(retry.get(1, Boolean::class.java), "tried again later")

        sql { db ->
            db.execute("UPDATE email_outbox SET sent_at = now() - INTERVAL '8 days' WHERE subject = 'Notify one' AND member_id = ?", player)
            db.execute("UPDATE email_outbox SET created_at = now() - INTERVAL '3 days' WHERE subject = 'Notify three' AND member_id = ?", player)
            MailWorker.cleanup(db)
        }
        assertEquals(emptyList<String>(), outbox().filter { it[0] == "player" }.map { it[2] }, "old rows are deleted")
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database), notifier = notifier) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, handle: String?, body: String? = null, withCsrf: String? = csrf): HttpResponse =
        client.request("/api/v1/me/notifications") {
            this.method = method
            if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            if (method != HttpMethod.Get) { header(HttpHeaders.Origin, origin); if (withCsrf != null) header("X-CSRF-Token", withCsrf) }
            if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
        }

    @Test
    fun membersReadAndChangeOnlyTheirOwnSettings() = app {
        val defaults = Json.parseToJsonElement(send(HttpMethod.Get, "player").bodyAsText()).jsonObject
        assertEquals(Json.parseToJsonElement("""{"eventChanges":true,"newEvents":true,"announcements":true,"shiftReminders":true,
            "helpersWanted":true,"answerReminders":true,"language":"de","emailEnabled":true,"hasEmail":true}"""), defaults)
        val body = """{"eventChanges":true,"newEvents":false,"announcements":true,"shiftReminders":false,"language":"fr"}"""
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "player", body).status)
        val changed = Json.parseToJsonElement(send(HttpMethod.Get, "player").bodyAsText()).jsonObject
        assertEquals("false", changed.getValue("newEvents").toString())
        assertEquals("\"fr\"", changed.getValue("language").toString())
        assertEquals("true", Json.parseToJsonElement(send(HttpMethod.Get, "outsider").bodyAsText()).jsonObject.getValue("newEvents").toString(),
            "another member is untouched")
        // helpersWanted (1.24.0) is optional: a body without it keeps the stored choice.
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "player", body.replace("}", ",\"helpersWanted\":false}")).status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "player", body).status)
        assertEquals("false", Json.parseToJsonElement(send(HttpMethod.Get, "player").bodyAsText()).jsonObject.getValue("helpersWanted").toString())

        val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
        }
        expect("no session", send(HttpMethod.Get, null), HttpStatusCode.Unauthorized)
        expect("no member", send(HttpMethod.Get, "stranger"), HttpStatusCode.Forbidden)
        expect("change without CSRF", send(HttpMethod.Put, "player", body, withCsrf = null), HttpStatusCode.Forbidden)
        expect("change without member", send(HttpMethod.Put, "stranger", body), HttpStatusCode.Forbidden)
        for (broken in listOf(body.replace("\"fr\"", "\"es\""), body.replace("false,\"announcements\"", "\"false\",\"announcements\""),
            body.replace(",\"language\":\"fr\"", ""), body.replace("}", ",\"email\":\"x@example.invalid\"}"), body.replace("}", ",\"helpersWanted\":\"no\"}"), body.replace("}", ",\"answerReminders\":1}"))) {
            expect(broken, send(HttpMethod.Put, "player", broken), HttpStatusCode.BadRequest)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
        sql { it.execute("DELETE FROM notification_settings WHERE member_id = ?", memberOf.getValue("player")) }
    }
}
