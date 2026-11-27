package org.openclub.api.teams

import org.jooq.DSLContext
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import java.time.LocalDate
import java.util.UUID

/**
 * Absences (OpenAPI 1.20.0, ADR-0008 addendum 24). A member enters calendar days (club time zone) when they are away,
 * a guardian does so for their current minor. Since 1.39.0 (addendum 44, reversing addendum 24) an absence may carry
 * a note of at most 100 characters, which coaches see next to the implied No. The view `effective_attendance` (V32) turns every
 * unanswered event of the member's teams on those days into NO; an explicit answer wins. Every check is inside the
 * statement that reads or changes data. Not audited, like attendance.
 */
internal class Absences(private val database: DSLContext) {
    data class Absence(val id: UUID, val memberId: UUID, val firstName: String, val from: LocalDate, val to: LocalDate, val note: String? = null)
    data class Person(val id: UUID, val firstName: String)
    data class Listing(val items: List<Absence>, val people: List<Person>)

    sealed interface Created {
        data class Done(val id: UUID) : Created
        data object Invalid : Created
        data object Denied : Created
    }

    /** The actor and their current minors, the people they may enter absences for. Binds the actor account. */
    private val people = """
        me AS (SELECT m.id, m.first_name FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}),
        people AS (
          SELECT me.id, me.first_name FROM me
          UNION
          SELECT minor.id, minor.first_name
          FROM me JOIN member_guardianships gs ON gs.guardian_member_id = me.id
          JOIN members guardian ON guardian.id = gs.guardian_member_id
          JOIN members minor ON minor.id = gs.minor_member_id
          JOIN clubs c ON TRUE
          WHERE ${AgeSql.adult("guardian")} AND minor.archived_at IS NULL AND ${AgeSql.minor("minor")})"""

    /** Current and future absences of the actor and their minors; null for a non-member. */
    fun list(actor: UUID): Listing? {
        val persons = database.fetch("WITH $people SELECT id, first_name FROM people ORDER BY first_name, id", actor)
            .map { Person(it.get(0, UUID::class.java), it.get(1, String::class.java)) }
        if (persons.isEmpty()) return null
        val items = database.fetch(
            """WITH $people
               SELECT ab.id, ab.member_id, p.first_name, ab.from_date, ab.to_date, ab.note
               FROM member_absences ab JOIN people p ON p.id = ab.member_id
               WHERE ab.to_date >= (now() AT TIME ZONE club_time_zone())::date
               ORDER BY ab.from_date, ab.to_date, ab.id""", actor).map {
            Absence(it.get(0, UUID::class.java), it.get(1, UUID::class.java), it.get(2, String::class.java),
                it.get(3, LocalDate::class.java), it.get(4, LocalDate::class.java), it.get(5, String::class.java))
        }
        return Listing(items, persons)
    }

    /**
     * Adds an absence for [memberId] if the actor may act for them: from today (club time zone) on, at most 366 days
     * long, ending within two years, and at most [MAX_OPEN] current or future absences per member.
     */
    fun create(actor: UUID, memberId: UUID, from: LocalDate, to: LocalDate, note: String? = null): Created {
        val id = UUID.randomUUID()
        val inserted = database.execute(
            """WITH $people
               INSERT INTO member_absences (id, member_id, from_date, to_date, note)
               SELECT ?, p.id, ?::date, ?::date, ? FROM people p
               CROSS JOIN (SELECT (now() AT TIME ZONE club_time_zone())::date AS today) t
               WHERE p.id = ? AND ?::date >= t.today AND ?::date >= ?::date AND ?::date <= ?::date + 365
                 AND ?::date <= t.today + 730
                 AND (SELECT count(*) FROM member_absences ab WHERE ab.member_id = p.id AND ab.to_date >= t.today) < $MAX_OPEN""",
            actor, id, from.toString(), to.toString(), note, memberId, from.toString(), to.toString(), from.toString(),
            to.toString(), from.toString(), to.toString())
        if (inserted == 1) return Created.Done(id)
        val allowed = database.fetchOne("WITH $people SELECT 1 FROM people WHERE id = ?", actor, memberId) != null
        return if (allowed) Created.Invalid else Created.Denied
    }

    /** Deletes an absence of the actor or their minor; false for every other ID (known or not). */
    fun delete(actor: UUID, absenceId: UUID): Boolean = database.fetch(
        """WITH $people
           DELETE FROM member_absences ab USING people p WHERE ab.id = ? AND ab.member_id = p.id RETURNING 1""",
        actor, absenceId).isNotEmpty()

    companion object {
        const val MAX_OPEN = 20
    }
}
