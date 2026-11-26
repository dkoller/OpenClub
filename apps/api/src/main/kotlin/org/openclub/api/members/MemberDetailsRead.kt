package org.openclub.api.members

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.database.AuditEvents
import org.openclub.api.members.ClubCapabilityAccess.Capability
import java.util.UUID

/**
 * Restricted read of one member's private fields (privacy proposal section 2, owner-approved defaults).
 *
 * - A live club-wide EDIT_MEMBERS holder with a current verified identity reads every field, including of an
 *   archived member (solely so the record can be exported, reactivated or erased). A missing member is
 *   [Result.NotFound] for them only.
 * - A MANAGE_TEAMS holder (club-wide, or on a team the member belongs to) reads names, email, phone and the
 *   player flag of an **active member of that team**, and each such read writes an identifier-only
 *   `CONTACT_READ` audit event in the same transaction.
 * - Everyone else, including a manager asking about a member outside their teams and any request about an
 *   unknown member, gets [Result.Denied], so existence is not revealed.
 *
 * A field the caller may not read is absent; null means readable and not set.
 */
internal class MemberDetailsRead(private val database: DSLContext) {
    sealed interface Result {
        /** [version] is the row version for an editor's full read (the ETag for `If-Match`), null for a manager. */
        data class Found(val body: JsonObject, val version: Long? = null) : Result
        data object Denied : Result
        data object NotFound : Result
    }

    fun read(actorAccountId: UUID, memberId: UUID): Result = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        val editor = ActorSql.holdsClubWide(db, actorAccountId, Capability.EDIT_MEMBERS)
        if (editor) {
            val row = db.fetchOne(
                """SELECT first_name, last_name, date_of_birth, email, phone, gender, is_player, archived_at, row_version,
                          listed_publicly, js_person_number, account_id IS NOT NULL AS linked,
                          (SELECT expires_at FROM member_invitations i WHERE i.member_id = members.id
                             AND i.used_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > now()) AS open_until
                   FROM members WHERE id = ?""", memberId) ?: return@transactionResult Result.NotFound
            fun text(name: String): JsonElement = row.get(name)?.toString()?.let(::JsonPrimitive) ?: JsonNull
            return@transactionResult Result.Found(buildJsonObject {
                put("id", memberId.toString())
                put("firstName", text("first_name"))
                put("lastName", text("last_name"))
                put("dateOfBirth", text("date_of_birth"))
                put("email", text("email"))
                put("phone", text("phone"))
                put("gender", text("gender"))
                put("isPlayer", row.get("is_player", Boolean::class.java))
                put("archivedAt", text("archived_at"))
                put("hasLinkedLogin", row.get("linked", Boolean::class.java))
                put("listedPublicly", row.get("listed_publicly", Boolean::class.java))
                put("jsPersonNumber", text("js_person_number"))
                put("invitation", buildJsonObject {
                    val until = row.get("open_until")
                    put("status", if (until == null) "NONE" else "OPEN")
                    if (until != null) put("expiresAt", until.toString())
                })
            }, row.get("row_version", Long::class.java))
        }
        val row = db.fetchOne(
            """SELECT first_name, last_name, email, phone, is_player FROM members m
               WHERE m.id = ? AND m.archived_at IS NULL
                 AND EXISTS (SELECT 1 FROM member_team_assignments a WHERE a.member_id = m.id
                             AND ${ActorSql.holdsTeamCapability("a.team_id", Capability.MANAGE_TEAMS)})""",
            memberId, actorAccountId) ?: return@transactionResult Result.Denied
        AuditEvents.record(db, AuditEvents.Type.CONTACT_READ, actorAccountId, memberId)
        fun text(name: String): JsonElement = row.get(name)?.toString()?.let(::JsonPrimitive) ?: JsonNull
        Result.Found(buildJsonObject {
            put("id", memberId.toString())
            put("firstName", text("first_name"))
            put("lastName", text("last_name"))
            put("email", text("email"))
            put("phone", text("phone"))
            put("isPlayer", row.get("is_player", Boolean::class.java))
        })
    }
}
