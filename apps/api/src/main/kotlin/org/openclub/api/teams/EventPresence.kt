package org.openclub.api.teams

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.openclub.api.database.clubZone

/**
 * Actual attendance and the J+S export (OpenAPI 1.27.0, ADR-0008 addendum 31). Only holders of `MANAGE_TEAMS` for the
 * event's team (club-wide or team-scoped) read or record who came, checked inside the same statement as each read
 * and write. People: active team members as participants, active members with a team-scoped `MANAGE_TEAMS` grant
 * for the team as leaders (a leader who is also in the team counts as leader), and anyone already recorded. Names
 * follow the attendance rule (minors as first name and surname initial); no contacts, no birth dates.
 */
internal class EventPresence(private val database: DSLContext) {
    enum class Role { PARTICIPANT, LEADER }

    data class Person(val memberId: UUID, val displayName: String, val role: Role, val response: String?, val present: Boolean)

    sealed interface Listing {
        data class Found(val open: Boolean, val people: List<Person>) : Listing
        data object NotFound : Listing
        data object Denied : Listing
    }

    enum class Change { DONE, NOT_FOUND, DENIED, CLOSED, INVALID }

    // An unknown ID is 404 only for a club-wide MANAGE_TEAMS holder; a team-scoped coach cannot tell it from another
    // team's (security review 2026-11-26).
    private fun managesSomeTeam(actor: UUID): Boolean = ActorSql.holdsClubWide(database, actor, Capability.MANAGE_TEAMS)

    private fun missing(actor: UUID): Change = if (managesSomeTeam(actor)) Change.NOT_FOUND else Change.DENIED

    /** The people for event `?` (one bind: event ID), with their recorded role if any. Columns as in [Person]. */
    private val people = """
        WITH ev AS (SELECT e.id, e.team_id FROM team_events e WHERE e.id = ?),
             leaders AS (
               SELECT g.member_id FROM team_capability_grants g JOIN ev ON g.team_id = ev.team_id
               JOIN members m ON m.id = g.member_id AND m.archived_at IS NULL
               WHERE g.capability = 'MANAGE_TEAMS'),
             candidates AS (
               SELECT a.member_id, CASE WHEN a.member_id IN (SELECT member_id FROM leaders) THEN 'LEADER' ELSE 'PARTICIPANT' END AS role
               FROM member_team_assignments a JOIN ev ON a.team_id = ev.team_id
               JOIN members m ON m.id = a.member_id AND m.archived_at IS NULL
               UNION
               SELECT member_id, 'LEADER' FROM leaders
               WHERE member_id NOT IN (SELECT a.member_id FROM member_team_assignments a JOIN ev ON a.team_id = ev.team_id)),
             everyone AS (
               SELECT COALESCE(p.member_id, c.member_id) AS member_id, COALESCE(p.role, c.role) AS role,
                      p.member_id IS NOT NULL AS present
               FROM candidates c FULL JOIN (SELECT p.* FROM event_presence p JOIN ev ON p.event_id = ev.id) p
                 ON p.member_id = c.member_id)
        SELECT m.id AS member_id,
               CASE WHEN ${AgeSql.minor("m")} THEN m.first_name || ' ' || left(m.last_name, 1) || '.'
                    ELSE m.first_name || ' ' || m.last_name END AS display_name,
               everyone.role, everyone.present, ea.response
        FROM everyone JOIN members m ON m.id = everyone.member_id JOIN clubs c ON TRUE
        LEFT JOIN effective_attendance ea ON ea.event_id = (SELECT id FROM ev) AND ea.member_id = m.id"""

    fun read(actor: UUID, eventId: UUID): Listing {
        val event = database.fetchOne(
            """SELECT ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)} AS allowed,
                      (e.starts_at <= now() AND NOT e.cancelled) AS open
               FROM team_events e WHERE e.id = ?""", actor, eventId)
            ?: return if (managesSomeTeam(actor)) Listing.NotFound else Listing.Denied
        if (event.get("allowed", Boolean::class.java) != true) return Listing.Denied
        val rows = database.fetch("$people ORDER BY everyone.role, display_name, m.id", eventId).map {
            Person(it.get("member_id", UUID::class.java), it.get("display_name", String::class.java),
                Role.valueOf(it.get("role", String::class.java)), it.get("response", String::class.java),
                it.get("present", Boolean::class.java))
        }
        return Listing.Found(event.get("open", Boolean::class.java) == true, rows)
    }

    /**
     * Replaces who came. The event row is locked for the transaction, so two coaches saving at once apply one after
     * the other. Every ID must be one of the event's people; a member who was recorded and is no longer in the team
     * keeps the recorded role.
     */
    fun replace(actor: UUID, eventId: UUID, present: Set<UUID>): Change = database.transactionResult { config ->
        val db = DSL.using(config)
        val event = db.fetchOne(
            """SELECT ${ActorSql.holdsTeamCapability("e.team_id", Capability.MANAGE_TEAMS)} AS allowed,
                      (e.starts_at <= now() AND NOT e.cancelled) AS open
               FROM team_events e WHERE e.id = ? FOR UPDATE OF e""", actor, eventId)
            ?: return@transactionResult EventPresence(db).missing(actor)
        if (event.get("allowed", Boolean::class.java) != true) return@transactionResult Change.DENIED
        if (event.get("open", Boolean::class.java) != true) return@transactionResult Change.CLOSED
        val roles = db.fetch(people, eventId).associate {
            it.get("member_id", UUID::class.java) to it.get("role", String::class.java)
        }
        if (!roles.keys.containsAll(present)) return@transactionResult Change.INVALID
        db.execute(
            "DELETE FROM event_presence WHERE event_id = ? AND NOT (member_id = ANY (?::uuid[]))",
            eventId, present.map(UUID::toString).toTypedArray())
        present.forEach { member ->
            db.execute(
                """INSERT INTO event_presence (event_id, member_id, role) VALUES (?, ?, ?)
                   ON CONFLICT (event_id, member_id) DO NOTHING""", eventId, member, roles.getValue(member))
        }
        Change.DONE
    }

    data class JsRow(
        val personNumber: String, val role: Role, val date: LocalDate, val type: String,
        val time: String?, val minutes: Long?, val place: String?,
    )

    data class Check(val rows: List<JsRow>, val withoutNumber: List<String>, val trainingsWithoutPlace: List<String>)

    sealed interface Export {
        data class Found(val check: Check) : Export
        data object NotFound : Export
        data object Denied : Export
    }

    /**
     * The J+S attendance (NDS import "Anwesenheitskontrolle") for the team's trainings and matches that started on
     * [from]..[to] (dates in Swiss time) and were not cancelled: one row per recorded person with a J+S number.
     * People without a number and trainings without a place are reported instead, by name or title and date.
     * [audit] records one JS_ATTENDANCE_EXPORTED event per person whose number is in the file, in the same transaction.
     */
    fun export(actor: UUID, teamId: UUID, from: LocalDate, to: LocalDate, audit: Boolean): Export = database.transactionResult { config ->
        val db = DSL.using(config)
        val team = db.fetchOne(
            "SELECT ${ActorSql.holdsTeamCapability("t.id", Capability.MANAGE_TEAMS)} FROM teams t WHERE t.id = ?", actor, teamId)
            ?: return@transactionResult if (EventPresence(db).managesSomeTeam(actor)) Export.NotFound else Export.Denied
        if (team.get(0, Boolean::class.java) != true) return@transactionResult Export.Denied
        val records = db.fetch(
            """SELECT e.kind, e.title, e.starts_at, e.ends_at, e.location, p.role, m.js_person_number, m.id AS member_id,
                      CASE WHEN ${AgeSql.minor("m")} THEN m.first_name || ' ' || left(m.last_name, 1) || '.'
                           ELSE m.first_name || ' ' || m.last_name END AS display_name
               FROM team_events e
               JOIN event_presence p ON p.event_id = e.id
               JOIN members m ON m.id = p.member_id
               JOIN clubs c ON TRUE
               WHERE e.team_id = ? AND NOT e.cancelled AND e.kind IN ('TRAINING', 'MATCH') AND e.starts_at <= now()
                 AND e.starts_at >= (?::date)::timestamp AT TIME ZONE club_time_zone()
                 AND e.starts_at < ((?::date) + 1)::timestamp AT TIME ZONE club_time_zone()
               ORDER BY e.starts_at, e.id, p.role, display_name, m.id""",
            teamId, from.toString(), to.toString())
        val zone = db.clubZone()
        val rows = mutableListOf<JsRow>()
        val withoutNumber = sortedSetOf<String>()
        val withoutPlace = linkedSetOf<String>()
        val exported = linkedSetOf<UUID>()
        records.forEach {
            val starts = it.get("starts_at", OffsetDateTime::class.java).atZoneSameInstant(zone)
            val ends = it.get("ends_at", OffsetDateTime::class.java).atZoneSameInstant(zone)
            val training = it.get("kind", String::class.java) == "TRAINING"
            val place = it.get("location", String::class.java)?.let(::placeCell)
            if (training && place == null) withoutPlace += "${it.get("title", String::class.java)} ${starts.format(DATE)}"
            val number = it.get("js_person_number", String::class.java)
            if (number == null) {
                withoutNumber += it.get("display_name", String::class.java)
                return@forEach
            }
            exported += it.get("member_id", UUID::class.java)
            rows += JsRow(number, Role.valueOf(it.get("role", String::class.java)), starts.toLocalDate(),
                if (training) "Training" else "Wettkampf",
                if (training) starts.format(TIME) else null,
                if (training) java.time.Duration.between(starts, ends).toMinutes() else null,
                if (training) place else null)
        }
        // One event per person whose J+S number is in the file, so each member's audit trail shows who exported it.
        if (audit && exported.isNotEmpty()) db.execute(
            """INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
               SELECT 'JS_ATTENDANCE_EXPORTED', ?, u::uuid FROM unnest(?::text[]) AS u""",
            actor, exported.map(UUID::toString).toTypedArray())
        Export.Found(Check(rows, withoutNumber.toList(), withoutPlace.toList()))
    }

    companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** A place as one plain CSV cell: separators, quotes and line breaks become spaces, a formula start is dropped. */
        fun placeCell(value: String): String? =
            value.map { if (it == ';' || it == ',' || it == '"' || Character.isISOControl(it)) ' ' else it }
                .joinToString("").trim().trimStart('=', '+', '-', '@').trim().takeIf(String::isNotEmpty)

        /** The NDS attendance import file: semicolon-separated, header row in the documented order, CRLF lines. */
        fun csv(rows: List<JsRow>): String = buildString {
            append("PERSONENNUMMER;FUNKTION;DATUM;AKTIVITAETSTYP;ZEIT;DAUER;ORT\r\n")
            rows.forEach {
                append(listOf(it.personNumber, if (it.role == Role.LEADER) "Leiter/in" else "Teilnehmer/in",
                    it.date.format(DATE), it.type, it.time.orEmpty(), it.minutes?.toString().orEmpty(), it.place.orEmpty())
                    .joinToString(";"))
                append("\r\n")
            }
        }
    }
}
