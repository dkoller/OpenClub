package org.openclub.api.members

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import java.time.LocalDate
import java.util.UUID

/**
 * A member's own record (OpenAPI 1.9.0, ADR-0008 addendum 12). Only the signed-in member's own row is reachable:
 * it is found by the verified session's account ID, never by an ID from the request, and must be active with a
 * current verified identity ([ActorSql.activeVerified]). Members may change their own email and phone; names, birth
 * date, gender, player and role stay with editors. Reading one's own record is not audited; a change is
 * (`SELF_CONTACTS_UPDATED`). The version is the row version of OpenAPI 1.8.0, so neither an editor nor the member
 * silently overwrites the other's change.
 */
internal class MyProfile(private val database: DSLContext) {
    data class Team(val id: UUID, val name: String)

    data class Profile(
        val id: UUID, val firstName: String, val lastName: String, val dateOfBirth: LocalDate,
        val email: String?, val phone: String?, val gender: String?, val isPlayer: Boolean,
        val role: String, val teams: List<Team>, val version: Long,
    )

    sealed interface Replaced {
        data class Updated(val version: Long) : Replaced
        data object Changed : Replaced
        data object Denied : Replaced
    }

    fun read(actor: UUID): Profile? {
        val row = database.fetchOne(
            """SELECT me.id, me.first_name, me.last_name, me.date_of_birth, me.email, me.phone, me.gender,
                      me.is_player, me.role, me.row_version,
                      (SELECT COALESCE(json_agg(json_build_object('id', t.id, 'name', t.name) ORDER BY t.name, t.id),
                                       '[]'::json)::text
                       FROM member_team_assignments a JOIN teams t ON t.id = a.team_id
                       WHERE a.member_id = me.id) AS teams
               FROM members me
               WHERE me.account_id = ? AND ${ActorSql.activeVerified("me")}""", actor) ?: return null
        val teams = Json.parseToJsonElement(row.get("teams", String::class.java)).jsonArray.map {
            val team = it.jsonObject
            Team(UUID.fromString(team.getValue("id").jsonPrimitive.content), team.getValue("name").jsonPrimitive.content)
        }
        return Profile(
            row.get("id", UUID::class.java), row.get("first_name", String::class.java),
            row.get("last_name", String::class.java), row.get("date_of_birth", LocalDate::class.java),
            row.get("email", String::class.java), row.get("phone", String::class.java),
            row.get("gender", String::class.java), row.get("is_player", Boolean::class.java),
            row.get("role", String::class.java), teams, row.get("row_version", Long::class.java),
        )
    }

    /** Replaces only the actor's own email and phone, at [expectedVersion], audited in the same statement. */
    fun replaceContacts(actor: UUID, email: String?, phone: String?, expectedVersion: Long): Replaced {
        val version = database.fetchOne(
            """WITH updated AS (
                 UPDATE members me SET email = ?, phone = ?
                 WHERE me.account_id = ? AND ${ActorSql.activeVerified("me")} AND me.row_version = ?
                 RETURNING me.id, me.row_version),
               audited AS (
                 INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
                 SELECT 'SELF_CONTACTS_UPDATED', ?, id FROM updated)
               SELECT row_version FROM updated""",
            email, phone, actor, expectedVersion, actor,
        )?.get(0, Long::class.java)
        if (version != null) return Replaced.Updated(version)
        val member = database.fetchOne(
            "SELECT 1 FROM members me WHERE me.account_id = ? AND ${ActorSql.activeVerified("me")}", actor)
        return if (member != null) Replaced.Changed else Replaced.Denied
    }
}
