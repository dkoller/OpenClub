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

/**
 * Queues notification emails (OpenAPI 1.19.0, ADR-0008 addendum 23) in the caller's transaction, so a message is
 * queued exactly when the change it reports commits. Recipients are the members of the team and the guardians of
 * its current minor members (everyone for a club-wide announcement), but only active members with a current verified
 * sign-in and an email address who have not turned that kind off. Messages hold club, team, event or announcement
 * details and a link, never other members' names; a reminder for a child names that child to their guardian.
 * Without a mail configuration nothing is queued.
 */
internal class Notifier(private val config: MailConfig?, private val origin: String) {
    val enabled: Boolean get() = config != null
    private val zone: ZoneId get() = config?.zone ?: ZoneId.of("Europe/Zurich")

    enum class Kind(val column: String) {
        NEW_EVENTS("new_events"), EVENT_CHANGED("event_changes"), EVENT_CANCELLED("event_changes"),
        ANNOUNCEMENT("announcements"), SHIFT_REMINDER("shift_reminders"),
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
               SELECT gen_random_uuid(), u.member_id::uuid, ?, u.subject, u.body
               FROM unnest(?::text[], ?::text[], ?::text[]) AS u(member_id, subject, body)""",
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
           WHERE m.email IS NOT NULL AND ${ActorSql.activeVerified("m")} AND COALESCE(s.${kind.column}, TRUE)
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
    private val open: String, private val settings: String,
) {
    fun subject(club: String, label: String, what: String) = "[$club] $label: $what".replace(Regex("[\\r\\n]+"), " ")
    fun footer(origin: String) = "\n$open: $origin\n$settings"

    companion object {
        private val all = mapOf(
            "de" to Texts(Locale.GERMAN, "Neu", "Geändert", "Abgesagt", "Mitteilung", "Erinnerung", "Dieser Termin ist abgesagt.",
                "Ort", "Für", "In OpenClub ansehen", "Benachrichtigungen ändern Sie in OpenClub unter «Mein Profil»."),
            "en" to Texts(Locale.ENGLISH, "New", "Changed", "Cancelled", "Announcement", "Reminder", "This event is cancelled.",
                "Place", "For", "View in OpenClub", "Change your notifications in OpenClub under “My profile”."),
            "fr" to Texts(Locale.FRENCH, "Nouveau", "Modifié", "Annulé", "Communication", "Rappel", "Cet événement est annulé.",
                "Lieu", "Pour", "Voir dans OpenClub", "Modifiez vos notifications dans OpenClub sous « Mon profil »."),
            "it" to Texts(Locale.ITALIAN, "Nuovo", "Modificato", "Annullato", "Comunicazione", "Promemoria", "Questo evento è annullato.",
                "Luogo", "Per", "Vedi in OpenClub", "Modifica le notifiche in OpenClub sotto «Il mio profilo»."),
        )

        fun of(language: String): Texts = all[language] ?: all.getValue("de")
    }
}
