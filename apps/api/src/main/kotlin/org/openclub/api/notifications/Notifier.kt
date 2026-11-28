package org.openclub.api.notifications

import org.jooq.DSLContext
import org.jooq.Record
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.openclub.api.database.DatabaseRuntime

/**
 * Queues notification emails (OpenAPI 1.19.0, ADR-0008 addendum 23) in the caller's transaction, so a message is
 * queued exactly when the change it reports commits. Recipients are the members of the team and the guardians of
 * its current minor members (everyone for a club-wide announcement), but only active members with a current verified
 * sign-in and an email address who have not turned that kind off. Messages hold club, team, event or announcement
 * details and a link, never other members' names; a reminder for a child names that child to their guardian.
 * Without a mail configuration nothing is queued.
 */
internal class Notifier(private val config: MailConfig?, private val origin: String, val push: PushConfig? = null) {
    /** Messages are queued when the club sends email or push notifications (1.33.0). */
    val enabled: Boolean get() = config != null || push != null
    private val zone: ZoneId get() = config?.zone ?: DatabaseRuntime.clubZone

    enum class Kind(val column: String) {
        NEW_EVENTS("new_events"), EVENT_CHANGED("event_changes"), EVENT_CANCELLED("event_changes"),
        ANNOUNCEMENT("announcements"), SHIFT_REMINDER("shift_reminders"), HELPERS_WANTED("helpers_wanted"),
        ANSWER_REMINDER("answer_reminders"), SQUAD_CALLED("squad_calls"), TEAM_MESSAGE("team_messages"),
    }

    /** What an email says about one event; kept for deleted events too. */
    data class EventDetails(
        val teamId: UUID, val teamName: String, val title: String, val startsAt: OffsetDateTime,
        val endsAt: OffsetDateTime, val location: String?, val notes: String?,
    )

    private data class Recipient(val memberId: UUID, val language: String)

    /** New events of one team (a weekly series is one message). Past events are left out. */
    fun eventsCreated(db: DSLContext, ids: List<UUID>) {
        if (!enabled || ids.isEmpty()) return
        val events = events(db, ids).filter { it.endsAt.isAfter(OffsetDateTime.now()) }
        val first = events.firstOrNull() ?: return
        queue(db, Kind.NEW_EVENTS, recipients(db, team, arrayOf<Any>(first.teamId), Kind.NEW_EVENTS)) { text, club ->
            val count = if (events.size > 1) " (${events.size})" else ""
            Message(text.subject(club, text.newEvents, "${first.teamName} – ${first.title}$count"),
                buildString {
                    appendLine("${first.teamName}: ${first.title}")
                    events.forEach { appendLine(when_(it, text)) }
                    first.location?.let { appendLine("${text.place}: $it") }
                    first.notes?.let { appendLine(); appendLine(it) }
                    append(text.footer(origin))
                })
        }
    }

    /** A changed, cancelled or reinstated event that has not ended (deleted ones: [eventCancelled]). */
    fun eventChanged(db: DSLContext, id: UUID, cancelled: Boolean) {
        if (!enabled) return
        val event = events(db, listOf(id)).firstOrNull()?.takeIf { it.endsAt.isAfter(OffsetDateTime.now()) } ?: return
        eventMessage(db, event, if (cancelled) Kind.EVENT_CANCELLED else Kind.EVENT_CHANGED)
    }

    fun eventCancelled(db: DSLContext, event: EventDetails) {
        if (!enabled || !event.endsAt.isAfter(OffsetDateTime.now())) return
        eventMessage(db, event, Kind.EVENT_CANCELLED)
    }

    private fun eventMessage(db: DSLContext, event: EventDetails, kind: Kind) =
        queue(db, kind, recipients(db, team, arrayOf<Any>(event.teamId), kind)) { text, club ->
            Message(text.subject(club, if (kind == Kind.EVENT_CANCELLED) text.cancelled else text.changed, "${event.teamName} – ${event.title}"),
                buildString {
                    appendLine("${event.teamName}: ${event.title}")
                    appendLine(when_(event, text))
                    if (kind == Kind.EVENT_CANCELLED) appendLine(text.cancelledLine)
                    event.location?.let { appendLine("${text.place}: $it") }
                    event.notes?.let { appendLine(); appendLine(it) }
                    append(text.footer(origin))
                })
        }

    /** A new announcement for its audience: the team, or every member when it is club-wide. */
    fun announcementPosted(db: DSLContext, id: UUID) {
        if (!enabled) return
        val row = db.fetchOne(
            """SELECT a.team_id, t.name AS team_name, a.title, a.body FROM announcements a
               LEFT JOIN teams t ON t.id = a.team_id WHERE a.id = ?""", id) ?: return
        val teamId = row.get("team_id", UUID::class.java)
        val people = if (teamId == null) recipients(db, "SELECT m.id FROM members m", emptyArray<Any>(), Kind.ANNOUNCEMENT)
        else recipients(db, team, arrayOf<Any>(teamId), Kind.ANNOUNCEMENT)
        val title = row.get("title", String::class.java)
        queue(db, Kind.ANNOUNCEMENT, people) { text, club ->
            Message(text.subject(club, text.announcement, title), buildString {
                row.get("team_name", String::class.java)?.let { appendLine(it) }
                appendLine(title)
                appendLine()
                appendLine(row.get("body", String::class.java))
                append(text.footer(origin))
            })
        }
    }

    /**
     * Queues the reminders for sign-ups whose shift starts within the next 24 hours, once per sign-up (recorded in
     * `shift_reminders_sent` in the same transaction). The volunteer gets it, or for a current minor their guardians.
     * Returns how many sign-ups were handled.
     */
    fun queueShiftReminders(db: DSLContext, limit: Int = 200): Int {
        if (!enabled) return 0
        val due = db.fetch(
            """SELECT v.shift_id, v.member_id, s.title AS shift_title, s.starts_at, s.ends_at, e.title AS event_title,
                      t.name AS team_name, m.first_name
               FROM shift_volunteers v
               JOIN event_shifts s ON s.id = v.shift_id
               JOIN team_events e ON e.id = s.event_id
               JOIN teams t ON t.id = e.team_id
               JOIN members m ON m.id = v.member_id
               WHERE s.starts_at > now() AND s.starts_at <= now() + INTERVAL '24 hours' AND NOT e.cancelled
                 AND NOT EXISTS (SELECT 1 FROM shift_reminders_sent r WHERE r.shift_id = v.shift_id AND r.member_id = v.member_id)
               ORDER BY s.starts_at LIMIT ? FOR UPDATE OF v SKIP LOCKED""", limit)
        for (row in due) {
            val volunteer = row.get("member_id", UUID::class.java)
            val inserted = db.execute(
                "INSERT INTO shift_reminders_sent (shift_id, member_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                row.get("shift_id", UUID::class.java), volunteer)
            if (inserted == 0) continue
            val people = recipients(db, volunteerOnly, arrayOf<Any>(volunteer), Kind.SHIFT_REMINDER)
            val shift = EventDetails(UUID(0, 0), row.get("team_name", String::class.java), row.get("shift_title", String::class.java),
                row.get("starts_at", OffsetDateTime::class.java), row.get("ends_at", OffsetDateTime::class.java), null, null)
            queueFor(db, Kind.SHIFT_REMINDER, people) { text, club ->
                Message(text.subject(club, text.reminder, "${shift.title} – ${shift.teamName}"), buildString {
                    appendLine("${shift.title}: ${when_(shift, text)}")
                    appendLine("${shift.teamName}: ${row.get("event_title", String::class.java)}")
                    append(text.footer(origin))
                }) to volunteer
            }
        }
        return due.size
    }

    /**
     * Helpers wanted (OpenAPI 1.24.0, ADR-0008 addendum 28): shifts that start in more than a day and at most seven
     * days and still have free places are called once each (recorded in `helper_calls_sent` in the same transaction).
     * One message per event goes to the team and the guardians of its minors, except people who already signed up for
     * a shift of that event and the guardians of children who did. Counts only, never names. Returns how many shifts
     * were called.
     */
    fun queueHelperCalls(db: DSLContext, limit: Int = 200): Int {
        if (!enabled) return 0
        val due = db.fetch(
            """SELECT s.id AS shift_id, s.title AS shift_title, s.starts_at, s.ends_at, s.needed, f.filled,
                      e.id AS event_id, e.team_id, t.name AS team_name, e.title, e.starts_at AS event_starts,
                      e.ends_at AS event_ends, e.location
               FROM event_shifts s
               JOIN team_events e ON e.id = s.event_id
               JOIN teams t ON t.id = e.team_id
               CROSS JOIN LATERAL (SELECT count(*)::int AS filled FROM shift_volunteers v WHERE v.shift_id = s.id) f
               WHERE NOT e.cancelled AND f.filled < s.needed
                 AND s.starts_at > now() + INTERVAL '1 day' AND s.starts_at <= now() + INTERVAL '7 days'
                 AND NOT EXISTS (SELECT 1 FROM helper_calls_sent h WHERE h.shift_id = s.id)
               ORDER BY e.id, s.starts_at, s.title, s.id LIMIT ? FOR UPDATE OF s SKIP LOCKED""", limit)
        val called = due.filter { row ->
            db.execute("INSERT INTO helper_calls_sent (shift_id) VALUES (?) ON CONFLICT DO NOTHING",
                row.get("shift_id", UUID::class.java)) == 1
        }
        for ((eventId, shifts) in called.groupBy { it.get("event_id", UUID::class.java) }) {
            val first = shifts.first()
            val event = EventDetails(first.get("team_id", UUID::class.java), first.get("team_name", String::class.java),
                first.get("title", String::class.java), first.get("event_starts", OffsetDateTime::class.java),
                first.get("event_ends", OffsetDateTime::class.java), first.get("location", String::class.java), null)
            val helping = db.fetch(
                """SELECT v.member_id AS id FROM shift_volunteers v JOIN event_shifts s ON s.id = v.shift_id WHERE s.event_id = ?
                   UNION
                   SELECT gs.guardian_member_id FROM member_guardianships gs
                   JOIN shift_volunteers v ON v.member_id = gs.minor_member_id
                   JOIN event_shifts s ON s.id = v.shift_id WHERE s.event_id = ?""", eventId, eventId)
                .map { it.get("id", UUID::class.java) }.toSet()
            val people = recipients(db, team, arrayOf<Any>(event.teamId), Kind.HELPERS_WANTED).filter { it.memberId !in helping }
            queue(db, Kind.HELPERS_WANTED, people) { text, club ->
                Message(text.subject(club, text.helpersWanted, "${event.teamName} – ${event.title}"), buildString {
                    appendLine("${event.teamName}: ${event.title}")
                    appendLine(when_(event, text))
                    event.location?.let { appendLine("${text.place}: $it") }
                    appendLine()
                    shifts.forEach { shift ->
                        val times = EventDetails(event.teamId, event.teamName, "", shift.get("starts_at", OffsetDateTime::class.java),
                            shift.get("ends_at", OffsetDateTime::class.java), null, null)
                        val needed = shift.get("needed", Int::class.java)
                        val free = needed - shift.get("filled", Int::class.java)
                        appendLine("${shift.get("shift_title", String::class.java)}, ${when_(times, text)}: ${text.freePlaces.format(free, needed)}")
                    }
                    append(text.footer("$origin/#/schedule/$eventId"))
                })
            }
        }
        return called.size
    }

    /**
     * Answer reminders (OpenAPI 1.25.0, ADR-0008 addendum 29): one message per team member without an answer (an
     * explicit one or an absence) to that member and, for a current minor, to their guardians, naming the child to the
     * guardian. The caller records the run in `answer_reminders` in the same transaction. Returns how many members
     * have not answered (also without a mail configuration, so a coach learns the number).
     */
    fun answerReminders(db: DSLContext, eventId: UUID): Int {
        val missing = db.fetch(
            """SELECT m.id FROM team_events e
               JOIN member_team_assignments a ON a.team_id = e.team_id
               JOIN members m ON m.id = a.member_id AND m.archived_at IS NULL
               WHERE e.id = ? AND NOT EXISTS (SELECT 1 FROM effective_attendance ea WHERE ea.event_id = e.id AND ea.member_id = m.id)
               ORDER BY m.id""", eventId).map { it.get(0, UUID::class.java) }
        if (!enabled || missing.isEmpty()) return missing.size
        val event = events(db, listOf(eventId)).firstOrNull() ?: return missing.size
        for (member in missing) {
            val people = recipients(db, volunteerOnly, arrayOf<Any>(member), Kind.ANSWER_REMINDER)
            queueFor(db, Kind.ANSWER_REMINDER, people) { text, club ->
                Message(text.subject(club, text.answerReminder, "${event.teamName} – ${event.title}"), buildString {
                    appendLine("${event.teamName}: ${event.title}")
                    appendLine(when_(event, text))
                    event.location?.let { appendLine("${text.place}: $it") }
                    appendLine()
                    appendLine(text.answerPlease)
                    append(text.footer("$origin/#/schedule/$eventId"))
                }) to member
            }
        }
        return missing.size
    }

    /**
     * The automatic run: events that start in more than one day and at most two days and are not cancelled get one
     * reminder each (recorded first, so it never repeats). An event with an answer deadline (1.39.0) gets it instead
     * in the 24 hours before the deadline (at least an hour before it). Returns how many events were handled.
     */
    fun queueAnswerReminders(db: DSLContext, limit: Int = 50): Int {
        if (!enabled) return 0
        val due = db.fetch(
            """SELECT e.id FROM team_events e
               WHERE NOT e.cancelled AND CASE WHEN e.answers_until IS NULL
                         THEN e.starts_at > now() + INTERVAL '1 day' AND e.starts_at <= now() + INTERVAL '2 days'
                         ELSE e.answers_until > now() + INTERVAL '1 hour' AND e.answers_until <= now() + INTERVAL '1 day' END
                 AND NOT EXISTS (SELECT 1 FROM answer_reminders r WHERE r.event_id = e.id AND r.automatic)
               ORDER BY e.starts_at LIMIT ? FOR NO KEY UPDATE OF e SKIP LOCKED""", limit).map { it.get(0, UUID::class.java) }
        for (event in due) {
            val recorded = db.execute(
                "INSERT INTO answer_reminders (event_id, automatic) VALUES (?, TRUE) ON CONFLICT DO NOTHING", event)
            if (recorded == 1) answerReminders(db, event)
        }
        return due.size
    }

    /**
     * A bulk change (OpenAPI 1.29.0, ADR-0008 addendum 33): one message per recipient listing every changed or
     * cancelled event of their teams (and their minors' teams), instead of one message per event.
     */
    fun eventsBulkChanged(db: DSLContext, ids: List<UUID>, cancelled: Boolean) {
        if (!enabled || ids.isEmpty()) return
        val kind = if (cancelled) Kind.EVENT_CANCELLED else Kind.EVENT_CHANGED
        val events = events(db, ids).filter { it.endsAt.isAfter(OffsetDateTime.now()) }
        val byPerson = linkedMapOf<Recipient, MutableList<EventDetails>>()
        for ((teamId, teamEvents) in events.groupBy { it.teamId }) {
            recipients(db, team, arrayOf<Any>(teamId), kind).forEach { byPerson.getOrPut(it) { mutableListOf() } += teamEvents }
        }
        for ((person, list) in byPerson) {
            queue(db, kind, listOf(person)) { text, club ->
                Message(text.subject(club, if (cancelled) text.cancelled else text.changed, "${list.size} × ${list.first().teamName}"),
                    buildString {
                        list.sortedBy { it.startsAt }.forEach { event ->
                            appendLine("${event.teamName}: ${event.title}, ${when_(event, text)}${event.location?.let { " – ${text.place}: $it" }.orEmpty()}")
                        }
                        append(text.footer(origin))
                    })
            }
        }
    }

    /**
     * A coach message (OpenAPI 1.31.0, ADR-0008 addendum 35): the coach's subject and text, with the event and the
     * sender's name, to each of [members] and the guardians of a current minor among them (naming the child).
     */
    fun coachMessage(db: DSLContext, eventId: UUID, members: List<UUID>, sender: String, subject: String, text: String) {
        if (!enabled || members.isEmpty()) return
        val event = events(db, listOf(eventId)).firstOrNull() ?: return
        for (member in members) {
            val people = recipients(db, volunteerOnly, arrayOf<Any>(member), Kind.TEAM_MESSAGE)
            queueFor(db, Kind.TEAM_MESSAGE, people) { words, club ->
                Message(words.subject(club, event.teamName, subject), buildString {
                    appendLine("${event.teamName}: ${event.title}, ${when_(event, words)}")
                    appendLine("${words.from}: $sender")
                    appendLine()
                    appendLine(text)
                    append(words.footer("$origin/#/schedule/$eventId"))
                }) to member
            }
        }
    }

    /**
     * Squad calls (OpenAPI 1.28.0, ADR-0008 addendum 32): one message per newly called member to that member and, for a
     * current minor, their guardians (naming the child), with the match, the meeting time and the meeting point.
     */
    fun squadCalled(db: DSLContext, eventId: UUID, members: Collection<UUID>) {
        if (!enabled || members.isEmpty()) return
        val event = events(db, listOf(eventId)).firstOrNull()?.takeIf { it.startsAt.isAfter(OffsetDateTime.now()) } ?: return
        val squad = db.fetchOne("SELECT meet_at, meeting_point FROM event_squads WHERE event_id = ?", eventId)
        val meetAt = squad?.get("meet_at", OffsetDateTime::class.java)
        val point = squad?.get("meeting_point", String::class.java)
        for (member in members.sorted()) {
            val people = recipients(db, volunteerOnly, arrayOf<Any>(member), Kind.SQUAD_CALLED)
            queueFor(db, Kind.SQUAD_CALLED, people) { text, club ->
                Message(text.subject(club, text.squadCalled, "${event.teamName} – ${event.title}"), buildString {
                    appendLine("${event.teamName}: ${event.title}")
                    appendLine(when_(event, text))
                    event.location?.let { appendLine("${text.place}: $it") }
                    meetAt?.let { appendLine("${text.meetAt}: ${DateTimeFormatter.ofPattern("EEE d. MMM uuuu, HH:mm", text.locale).format(it.atZoneSameInstant(zone))}") }
                    point?.let { appendLine("${text.meetingPoint}: $it") }
                    append(text.footer("$origin/#/schedule/$eventId"))
                }) to member
            }
        }
    }

    /**
     * The first ride request of an event (OpenAPI 1.41.0, ADR-0008 addendum 44): one message to each driver already
     * offering a ride to it (not [member] themselves), without the name of who asked, under the `teamMessages` switch.
     */
    fun rideRequested(db: DSLContext, eventId: UUID, member: UUID) {
        if (!enabled) return
        val event = events(db, listOf(eventId)).firstOrNull()?.takeIf { it.startsAt.isAfter(OffsetDateTime.now()) } ?: return
        val drivers = recipients(db, "SELECT r.driver_member_id AS id FROM event_rides r WHERE r.event_id = ? AND r.driver_member_id <> ?",
            arrayOf<Any>(eventId, member), Kind.TEAM_MESSAGE)
        queue(db, Kind.TEAM_MESSAGE, drivers) { text, club ->
            Message(text.subject(club, text.rideWanted, "${event.teamName} – ${event.title}"), buildString {
                appendLine("${event.teamName}: ${event.title}")
                appendLine(when_(event, text))
                appendLine()
                appendLine(text.rideWantedLine)
                append(text.footer("$origin/#/schedule/$eventId"))
            })
        }
    }

    private data class Message(val subject: String, val body: String)

    private fun queue(db: DSLContext, kind: Kind, people: List<Recipient>, render: (Texts, String) -> Message) =
        queueFor(db, kind, people) { text, club -> render(text, club) to null }

    /** [render] may return a volunteer: a guardian's copy then says whom the reminder is for. */
    private fun queueFor(db: DSLContext, kind: Kind, people: List<Recipient>, render: (Texts, String) -> Pair<Message, UUID?>) {
        if (people.isEmpty()) return
        val club = db.fetchValue("SELECT name FROM clubs LIMIT 1")?.toString() ?: "OpenClub"
        val volunteerName = mutableMapOf<UUID, String?>()
        val rows = people.map { person ->
            val text = Texts.of(person.language)
            val (message, volunteer) = render(text, club)
            val body = if (volunteer != null && volunteer != person.memberId) {
                val name = volunteerName.getOrPut(volunteer) {
                    db.fetchValue("SELECT first_name FROM members WHERE id = ?", volunteer)?.toString()
                }
                "${text.forPerson}: ${name.orEmpty()}\n${message.body}"
            } else message.body
            Triple(person.memberId, message.subject.take(300), body.take(20000))
        }
        db.execute(
            """INSERT INTO email_outbox (id, member_id, kind, subject, body)
               SELECT gen_random_uuid(), u.member_id::uuid, ?, u.subject, CASE WHEN m.email IS NULL THEN '' ELSE u.body END
               FROM unnest(?::text[], ?::text[], ?::text[]) AS u(member_id, subject, body)
               -- Push-only recipients get no stored text: their devices show the subject only.
               JOIN members m ON m.id = u.member_id::uuid""",
            kind.name, rows.map { it.first.toString() }.toTypedArray(), rows.map { it.second }.toTypedArray(),
            rows.map { it.third }.toTypedArray())
    }

    /** The team's members (one `?` bind: team ID). */
    private val team = "SELECT a.member_id AS id FROM member_team_assignments a WHERE a.team_id = ?"

    /** The volunteer (one `?` bind); [recipients] adds the guardians when the volunteer is a current minor. */
    private val volunteerOnly = "SELECT m.id FROM members m WHERE m.id = ?"

    /**
     * Who receives [kind] among [people] (a SELECT of `id`) and the guardians of current minors among them: active,
     * verified, with an email address and the kind switched on (no settings row means on).
     */
    private fun recipients(db: DSLContext, people: String, binds: Array<Any>, kind: Kind): List<Recipient> = db.fetch(
        """WITH people AS ($people),
                targets AS (
                  SELECT id FROM people
                  UNION
                  SELECT gs.guardian_member_id FROM member_guardianships gs
                  JOIN people p ON p.id = gs.minor_member_id
                  JOIN members minor ON minor.id = gs.minor_member_id
                  JOIN members guardian ON guardian.id = gs.guardian_member_id
                  JOIN clubs c ON TRUE
                  WHERE minor.archived_at IS NULL AND ${AgeSql.minor("minor")} AND ${AgeSql.adult("guardian")})
           SELECT m.id, COALESCE(s.language, 'de') AS language
           FROM targets t JOIN members m ON m.id = t.id
           LEFT JOIN notification_settings s ON s.member_id = m.id
           WHERE (m.email IS NOT NULL OR EXISTS (SELECT 1 FROM push_subscriptions ps WHERE ps.member_id = m.id))
             AND ${ActorSql.activeVerified("m")} AND COALESCE(s.${kind.column}, TRUE)
           ORDER BY m.id""", *binds).map { Recipient(it.get("id", UUID::class.java), it.get("language", String::class.java)) }

    private fun events(db: DSLContext, ids: List<UUID>): List<EventDetails> = db.fetch(
        """SELECT e.team_id, t.name AS team_name, e.title, e.starts_at, e.ends_at, e.location, e.notes
           FROM team_events e JOIN teams t ON t.id = e.team_id
           WHERE e.id = ANY(?::uuid[]) ORDER BY e.starts_at, e.id""", ids.toTypedArray()).map(::details)

    /** "Mi., 25. Nov. 2026, 18:00–19:30" in the recipient's language and the club's time zone. */
    private fun when_(event: EventDetails, text: Texts): String {
        val from = event.startsAt.atZoneSameInstant(zone)
        val to = event.endsAt.atZoneSameInstant(zone)
        val day = DateTimeFormatter.ofPattern("EEE d. MMM uuuu, HH:mm", text.locale)
        val time = DateTimeFormatter.ofPattern("HH:mm", text.locale)
        return day.format(from) + "–" + (if (from.toLocalDate() == to.toLocalDate()) time else day).format(to)
    }

    companion object {
        val DISABLED = Notifier(null, "")

        fun details(row: Record) = EventDetails(
            row.get("team_id", UUID::class.java), row.get("team_name", String::class.java), row.get("title", String::class.java),
            row.get("starts_at", OffsetDateTime::class.java), row.get("ends_at", OffsetDateTime::class.java),
            row.get("location", String::class.java), row.get("notes", String::class.java),
        )
    }
}

/** The fixed wording of notification emails in the four UI languages. */
internal data class Texts(
    val locale: Locale, val newEvents: String, val changed: String, val cancelled: String, val announcement: String,
    val reminder: String, val cancelledLine: String, val place: String, val forPerson: String,
    val helpersWanted: String, val freePlaces: String, val answerReminder: String, val answerPlease: String,
    private val open: String, private val settings: String,
    val squadCalled: String = "Aufgebot", val meetAt: String = "Treffpunkt", val meetingPoint: String = "Treffpunkt Ort",
    val from: String = "Von",
    val rideWanted: String = "Mitfahrt gesucht",
    val rideWantedLine: String = "Jemand aus dem Team sucht eine Mitfahrt. Freie Plätze bieten Sie in OpenClub beim Termin an.",
) {
    fun subject(club: String, label: String, what: String) = "[$club] $label: $what".replace(Regex("[\\r\\n]+"), " ")
    fun footer(origin: String) = "\n$open: $origin\n$settings"

    companion object {
        private val all = mapOf(
            "de" to Texts(Locale.GERMAN, "Neu", "Geändert", "Abgesagt", "Mitteilung", "Erinnerung", "Dieser Termin ist abgesagt.",
                "Ort", "Für", "Helfer gesucht", "%d von %d Plätzen frei", "Antwort fehlt",
                "Bitte antworten Sie in OpenClub mit Ja, Nein oder Vielleicht, damit das Team planen kann.", "In OpenClub ansehen",
                "Benachrichtigungen ändern Sie in OpenClub unter «Familie» → «Mein Profil».", "Aufgebot", "Besammlung", "Besammlungsort", "Von"),
            "en" to Texts(Locale.ENGLISH, "New", "Changed", "Cancelled", "Announcement", "Reminder", "This event is cancelled.",
                "Place", "For", "Helpers wanted", "%d of %d places free", "Answer missing",
                "Please answer Yes, No or Maybe in OpenClub so the team can plan.", "View in OpenClub",
                "Change your notifications in OpenClub under “Family” → “My profile”.", "Called up", "Meet at", "Meeting point", "From",
                "Ride wanted", "Someone in the team needs a ride. You can offer your free seats on the event in OpenClub."),
            "fr" to Texts(Locale.FRENCH, "Nouveau", "Modifié", "Annulé", "Communication", "Rappel", "Cet événement est annulé.",
                "Lieu", "Pour", "Bénévoles recherchés", "%d places libres sur %d", "Réponse manquante",
                "Veuillez répondre Oui, Non ou Peut-être dans OpenClub pour que l’équipe puisse s’organiser.", "Voir dans OpenClub",
                "Modifiez vos notifications dans OpenClub sous « Famille » → « Mon profil ».", "Convocation", "Rendez-vous", "Lieu de rendez-vous", "De",
                "Covoiturage recherché", "Une personne de l’équipe cherche une place. Proposez vos places libres sur l’événement dans OpenClub."),
            "it" to Texts(Locale.ITALIAN, "Nuovo", "Modificato", "Annullato", "Comunicazione", "Promemoria", "Questo evento è annullato.",
                "Luogo", "Per", "Cercasi aiutanti", "%d posti liberi su %d", "Risposta mancante",
                "Rispondete Sì, No o Forse in OpenClub, così la squadra può organizzarsi.", "Vedi in OpenClub",
                "Modifica le notifiche in OpenClub sotto «Famiglia» → «Il mio profilo».", "Convocazione", "Ritrovo", "Luogo di ritrovo", "Da",
                "Cercasi passaggio", "Qualcuno della squadra cerca un passaggio. Offrite i posti liberi sull’evento in OpenClub."),
        )

        fun of(language: String): Texts = all[language] ?: all.getValue("de")
    }
}
