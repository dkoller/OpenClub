package org.openclub.api.members

import kotlinx.serialization.json.Json
import org.openclub.api.members.ClubCapabilityAccess.Capability
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.openclub.api.database.AuditEvents
import org.openclub.api.database.rejectedTheData
import java.time.LocalDate
import java.util.UUID

/**
 * `<member>.date_of_birth` makes them an adult (or a minor) under the club's adult age. `c` is the clubs row.
 * Shared with the schedules, which show a guardian their minors' events under exactly the same rule.
 */
internal object AgeSql {
    private val alias = Regex("[a-z_]+")
    fun adult(member: String): String {
        require(alias.matches(member)) { "Invalid SQL alias" }
        return "$member.date_of_birth <= (CURRENT_DATE - make_interval(years => c.adult_age_years))::date"
    }
    fun minor(member: String): String {
        require(alias.matches(member)) { "Invalid SQL alias" }
        return "$member.date_of_birth > (CURRENT_DATE - make_interval(years => c.adult_age_years))::date"
    }
}

private fun adult(member: String) = AgeSql.adult(member)
private fun minor(member: String) = AgeSql.minor(member)

/**
 * Guardianships managed by editors (OpenAPI 1.6.0; design docs/security/guardian-design.md, approved 2026-11-19,
 * ADR-0008 addendum 8). An editor with a live EDIT_MEMBERS grant attests that an active adult member with a login
 * is a guardian of an active minor; at most two per minor (also enforced by V19). A guardianship grants no
 * capability. The editor's grant is locked for the transaction, so a concurrent revocation cannot be outrun.
 */
internal class MemberGuardianships(private val database: DSLContext) {
    sealed interface Listing {
        data class Found(val guardians: List<MemberDirectory.Summary>) : Listing
        data object NotFound : Listing
        data object Denied : Listing
    }
    enum class Change { DONE, ABSENT, NOT_FOUND, CONFLICT, DENIED }

    private fun editor(db: DSLContext, actor: UUID): Boolean = ActorSql.holdsClubWide(db, actor, Capability.EDIT_MEMBERS, lock = true)

    private fun activeMinor(db: DSLContext, id: UUID): Boolean = db.fetchOne(
        "SELECT 1 FROM members m JOIN clubs c ON TRUE WHERE m.id = ? AND m.archived_at IS NULL AND ${minor("m")}", id) != null

    fun list(actor: UUID, minorId: UUID): Listing = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        if (!editor(db, actor)) return@transactionResult Listing.Denied
        if (!activeMinor(db, minorId)) return@transactionResult Listing.NotFound
        Listing.Found(db.fetch(
            """SELECT g.id, g.first_name, g.last_name FROM member_guardianships gs
               JOIN members g ON g.id = gs.guardian_member_id
               WHERE gs.minor_member_id = ? ORDER BY g.last_name, g.first_name, g.id""", minorId).map {
            MemberDirectory.Summary(it.get(0, UUID::class.java), it.get(1, String::class.java), it.get(2, String::class.java))
        })
    }

    fun add(actor: UUID, minorId: UUID, guardianId: UUID): Change = try {
        database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            if (!editor(db, actor)) return@transactionResult Change.DENIED
            if (!activeMinor(db, minorId) ||
                db.fetchOne("SELECT 1 FROM members WHERE id = ?", guardianId) == null) return@transactionResult Change.NOT_FOUND
            val eligible = db.fetchOne(
                """SELECT 1 FROM members g JOIN clubs c ON TRUE
                   WHERE g.id = ? AND g.archived_at IS NULL AND g.account_id IS NOT NULL AND ${adult("g")}""", guardianId) != null
            if (!eligible || minorId == guardianId) return@transactionResult Change.CONFLICT
            // The V19 trigger locks the minor and enforces the limit of two; a duplicate is a conflict too.
            if (db.execute("""INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)
                              ON CONFLICT DO NOTHING""", minorId, guardianId) == 0) return@transactionResult Change.CONFLICT
            AuditEvents.record(db, AuditEvents.Type.GUARDIAN_LINKED, actor, minorId, relatedMemberId = guardianId)
            Change.DONE
        }
    } catch (failure: DataAccessException) {
        if (failure.rejectedTheData()) Change.CONFLICT else throw failure
    }

    fun remove(actor: UUID, minorId: UUID, guardianId: UUID): Change = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        if (!editor(db, actor)) return@transactionResult Change.DENIED
        if (db.execute("DELETE FROM member_guardianships WHERE minor_member_id = ? AND guardian_member_id = ?",
                minorId, guardianId) == 0) return@transactionResult Change.ABSENT
        AuditEvents.record(db, AuditEvents.Type.GUARDIAN_UNLINKED, actor, minorId, relatedMemberId = guardianId)
        Change.DONE
    }
}

/**
 * What a guardian may do for their own minors (OpenAPI 1.6.0). Access is by relationship, not capability: the
 * signed-in account's member must be active, verified and an adult, the minor active and still below the club's
 * adult age, and the guardianship must exist. All of it is checked in the same statement as each read or write,
 * so reaching the adult age, an archive, an unlink or a removal ends access at the next request.
 */
internal class Dependents(private val database: DSLContext) {
    data class Details(
        val id: UUID, val firstName: String, val lastName: String, val dateOfBirth: LocalDate,
        val email: String?, val phone: String?, val isPlayer: Boolean, val teams: List<String>,
    )

    /** Binds: guardian account ID, then the minor's member ID when [oneMinor] is true. */
    private fun relationship(oneMinor: Boolean) = """
        FROM member_guardianships gs
        JOIN members guardian ON guardian.id = gs.guardian_member_id
        JOIN members minor ON minor.id = gs.minor_member_id
        JOIN clubs c ON TRUE
        WHERE guardian.account_id = ? AND ${ActorSql.activeVerified("guardian")} AND ${adult("guardian")}
          AND minor.archived_at IS NULL AND ${minor("minor")}${if (oneMinor) " AND minor.id = ?" else ""}"""

    fun list(actor: UUID): List<MemberDirectory.Summary> = database.fetch(
        "SELECT minor.id, minor.first_name, minor.last_name ${relationship(false)} ORDER BY minor.last_name, minor.first_name, minor.id",
        actor).map { MemberDirectory.Summary(it.get(0, UUID::class.java), it.get(1, String::class.java), it.get(2, String::class.java)) }

    fun isGuardian(actor: UUID, minorId: UUID): Boolean =
        database.fetchOne("SELECT 1 ${relationship(true)}", actor, minorId) != null

    /** The minor's record as far as a guardian may read it, audited as DEPENDENT_READ; null for every refusal. */
    fun read(actor: UUID, minorId: UUID): Details? = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        val row = db.fetchOne(
            """SELECT minor.id, minor.first_name, minor.last_name, minor.date_of_birth, minor.email, minor.phone,
                      minor.is_player, guardian.id AS guardian_id,
                      (SELECT COALESCE(json_agg(t.name ORDER BY t.name), '[]'::json)::text
                       FROM member_team_assignments a JOIN teams t ON t.id = a.team_id
                       WHERE a.member_id = minor.id) AS teams
               ${relationship(true)}""", actor, minorId) ?: return@transactionResult null
        AuditEvents.record(db, AuditEvents.Type.DEPENDENT_READ, actor, minorId,
            relatedMemberId = row.get("guardian_id", UUID::class.java))
        val teams = Json.parseToJsonElement(row.get("teams", String::class.java)).jsonArray.map { it.jsonPrimitive.content }
        Details(row.get("id", UUID::class.java), row.get("first_name", String::class.java),
            row.get("last_name", String::class.java), row.get("date_of_birth", LocalDate::class.java),
            row.get("email", String::class.java), row.get("phone", String::class.java),
            row.get("is_player", Boolean::class.java), teams)
    }

    /** Replaces only email and phone, in one statement with the relationship check; false for every refusal. */
    fun replaceContacts(actor: UUID, minorId: UUID, email: String?, phone: String?): Boolean = database.execute(
        """WITH allowed AS (SELECT minor.id AS minor_id, guardian.id AS guardian_id ${relationship(true)}),
                updated AS (UPDATE members m SET email = ?, phone = ? FROM allowed WHERE m.id = allowed.minor_id
                            RETURNING m.id, allowed.guardian_id)
           INSERT INTO audit_events (event_type, actor_account_id, target_member_id, related_member_id)
           SELECT 'DEPENDENT_UPDATED', ?, id, guardian_id FROM updated""",
        actor, minorId, email, phone, actor) == 1
}
