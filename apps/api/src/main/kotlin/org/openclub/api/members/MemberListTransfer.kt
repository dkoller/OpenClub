package org.openclub.api.members

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Member list import and export for editors (OpenAPI 1.13.0, ADR-0008 addendum 17). Each runs in one transaction that
 * first takes a share lock on the actor's live `EDIT_MEMBERS` grant and member row. The export audits every listed
 * member as `MEMBER_LISTED`; the import only creates members (never changes one), skips rows matching an existing
 * member (same first name, last name and birth date, ignoring case, archived members included) and rows with problems,
 * and audits each created member as `MEMBER_CREATED`.
 */
internal class MemberListTransfer(private val database: DSLContext) {
    enum class Status { OK, DUPLICATE, INVALID }

    data class Checked(val row: MemberCsv.Row, val status: Status)

    data class Imported(val rows: List<Checked>, val created: Int?)

    private fun lockEditor(db: DSLContext, actor: UUID): Boolean = db.fetchOne(
        """SELECT 1 FROM members actor JOIN club_capability_grants g ON g.member_id = actor.id AND g.capability = 'EDIT_MEMBERS'
           WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")}
           FOR SHARE OF actor, g""", actor) != null

    /** Null without the grant. [table] is the parsed file; rows are checked against the club's current rules. */
    fun importRows(actor: UUID, table: List<List<String>>, commit: Boolean): Imported? = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        if (!lockEditor(db, actor)) return@transactionResult null
        // Serialize imports, so two at once cannot both create the same member.
        if (commit) db.execute("SELECT pg_advisory_xact_lock(hashtext('openclub-member-import'))")
        val club = db.fetchOne("SELECT adult_age_years, gender_required_for_players FROM clubs LIMIT 1")
            ?: return@transactionResult null
        val rows = MemberCsv.rows(table, club.get(0, Int::class.java), club.get(1, Boolean::class.java), LocalDate.now())
        // Lowercased in Kotlin, exactly like the file's rows, whatever the database collation.
        val existing = db.fetch("SELECT first_name, last_name, date_of_birth FROM members").map {
            Triple(it.get(0, String::class.java).lowercase(Locale.ROOT), it.get(1, String::class.java).lowercase(Locale.ROOT),
                it.get(2, LocalDate::class.java))
        }.toSet()
        val checked = rows.map { row ->
            val key = row.dateOfBirth?.let { Triple(row.firstName.lowercase(Locale.ROOT), row.lastName.lowercase(Locale.ROOT), it) }
            Checked(row, when {
                row.problems.isNotEmpty() -> Status.INVALID
                key in existing -> Status.DUPLICATE
                else -> Status.OK
            })
        }
        if (!commit) return@transactionResult Imported(checked, null)
        val creation = MemberCreation(db)
        val created = checked.filter { it.status == Status.OK }.count { item ->
            val row = item.row
            creation.create(actor, MemberCreation.Input(row.firstName, row.lastName, requireNotNull(row.dateOfBirth),
                row.email, row.phone, row.gender, row.isPlayer)) != null
        }
        Imported(checked, created)
    }

    /** Active members with their teams, as CSV in [language]; null without the grant. */
    fun export(actor: UUID, language: String): String? = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        if (!lockEditor(db, actor)) return@transactionResult null
        val rows = db.fetch(
            """SELECT m.id, m.first_name, m.last_name, m.date_of_birth, m.email, m.phone, m.gender, m.is_player, m.role,
                      COALESCE((SELECT json_agg(t.name ORDER BY t.name) FROM member_team_assignments a
                                JOIN teams t ON t.id = a.team_id WHERE a.member_id = m.id), '[]')::text AS teams
               FROM members m WHERE m.archived_at IS NULL
               ORDER BY m.last_name, m.first_name, m.id""")
        db.execute(
            """INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
               SELECT 'MEMBER_LISTED', ?, id FROM members WHERE id = ANY(?::uuid[])""",
            actor, rows.map { it.get("id", UUID::class.java) }.toTypedArray())
        MemberCsv.export(language, rows.map {
            MemberCsv.ExportRow(it.get("first_name", String::class.java), it.get("last_name", String::class.java),
                it.get("date_of_birth", LocalDate::class.java), it.get("email", String::class.java),
                it.get("phone", String::class.java), it.get("gender", String::class.java),
                it.get("is_player", Boolean::class.java), it.get("role", String::class.java),
                Json.parseToJsonElement(it.get("teams", String::class.java)).jsonArray.map { team -> team.jsonPrimitive.content })
        })
    }
}
