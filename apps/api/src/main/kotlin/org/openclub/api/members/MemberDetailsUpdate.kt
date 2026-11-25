package org.openclub.api.members

import org.jooq.DSLContext
import java.time.LocalDate
import java.util.UUID
import org.openclub.api.members.ClubCapabilityAccess.Capability

/**
 * Member field updates: session-derived actor, explicit EDIT_MEMBERS grant and
 * current verified identity are checked inside the modifying SQL statement.
 * The full replacement [update] backs `PUT /api/v1/members/{id}` (OpenAPI 1.4.0, owner-approved 2026-11-19). Since
 * OpenAPI 1.8.0 it applies only when the row still has the version the editor read (`If-Match`), so a change made in
 * between, such as a guardian's contact update, is never silently overwritten.
 */
internal class MemberDetailsUpdate(private val database: DSLContext) {
    data class Input(
        val firstName: String,
        val lastName: String,
        val dateOfBirth: LocalDate,
        val email: String? = null,
        val phone: String? = null,
        val gender: String? = null,
        val isPlayer: Boolean = false,
    )

    /**
     * Change only directory-visible names; leave DOB, contacts, gender,
     * identity, role and grants untouched; synthetic name-only HTTP is guarded.
     */
    fun updateNames(actorAccountId: UUID, memberId: UUID, firstName: String, lastName: String): Boolean {
        val first = MemberFieldValidation.name(firstName)
        val last = MemberFieldValidation.name(lastName)
        return database.execute(
            """WITH updated AS (
                 UPDATE members SET first_name = ?, last_name = ?
                 WHERE id = ? AND archived_at IS NULL AND ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}
                 RETURNING id)
               INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
               SELECT 'MEMBER_UPDATED', ?, id FROM updated""",
            first, last, memberId, actorAccountId, actorAccountId,
        ) == 1
    }

    sealed interface Replaced {
        data class Updated(val version: Long) : Replaced
        data object Denied : Replaced
        data object NotFound : Replaced
        data object Changed : Replaced
    }

    fun update(actorAccountId: UUID, memberId: UUID, input: Input, expectedVersion: Long): Replaced {
        val first = MemberFieldValidation.name(input.firstName)
        val last = MemberFieldValidation.name(input.lastName)
        val birthDate = MemberFieldValidation.birthDate(input.dateOfBirth)
        val email = MemberFieldValidation.email(input.email)
        val phone = MemberFieldValidation.phone(input.phone)
        val gender = MemberFieldValidation.gender(input.gender)

        val version = database.fetchOne(
            """WITH updated AS (
                 UPDATE members SET first_name = ?, last_name = ?, date_of_birth = ?,
                    email = ?, phone = ?, gender = ?, is_player = ?
                 WHERE id = ? AND archived_at IS NULL AND row_version = ?
                   AND ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}
                 RETURNING id, row_version),
               audited AS (
                 INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
                 SELECT 'MEMBER_UPDATED', ?, id FROM updated)
               SELECT row_version FROM updated""",
            first, last, birthDate, email, phone, gender,
            input.isPlayer, memberId, expectedVersion, actorAccountId, actorAccountId,
        )?.get(0, Long::class.java)
        if (version != null) return Replaced.Updated(version)
        // Nothing changed: the actor lost the grant, the member is unknown or archived, or it changed since it was read.
        if (!ClubCapabilityAccess(database).has(actorAccountId, Capability.EDIT_MEMBERS)) return Replaced.Denied
        val active = database.fetchOne("SELECT 1 FROM members WHERE id = ? AND archived_at IS NULL", memberId) != null
        return if (active) Replaced.Changed else Replaced.NotFound
    }

    /**
     * Sets or clears an active member's J+S person number (OpenAPI 1.27.0, ADR-0008 addendum 31), audited as
     * JS_NUMBER_CHANGED in the same statement. Same version rule as [update].
     */
    fun setJsNumber(actorAccountId: UUID, memberId: UUID, number: String?, expectedVersion: Long): Replaced {
        val version = database.fetchOne(
            """WITH updated AS (
                 UPDATE members SET js_person_number = ?
                 WHERE id = ? AND archived_at IS NULL AND row_version = ?
                   AND ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}
                 RETURNING id, row_version),
               audited AS (
                 INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
                 SELECT 'JS_NUMBER_CHANGED', ?, id FROM updated)
               SELECT row_version FROM updated""",
            number, memberId, expectedVersion, actorAccountId, actorAccountId,
        )?.get(0, Long::class.java)
        if (version != null) return Replaced.Updated(version)
        if (!ClubCapabilityAccess(database).has(actorAccountId, Capability.EDIT_MEMBERS)) return Replaced.Denied
        val active = database.fetchOne("SELECT 1 FROM members WHERE id = ? AND archived_at IS NULL", memberId) != null
        return if (active) Replaced.Changed else Replaced.NotFound
    }

    /**
     * Shows or hides an active member's name on the public member list (OpenAPI 1.15.0, controller decision C5: the
     * club honours a member's objection, including for minors who cannot sign in). Same version rule as [update].
     */
    fun setListing(actorAccountId: UUID, memberId: UUID, listed: Boolean, expectedVersion: Long): Replaced {
        val version = database.fetchOne(
            """WITH updated AS (
                 UPDATE members SET listed_publicly = ?
                 WHERE id = ? AND archived_at IS NULL AND row_version = ?
                   AND ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}
                 RETURNING id, row_version),
               audited AS (
                 INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
                 SELECT 'PUBLIC_LISTING_CHANGED', ?, id FROM updated)
               SELECT row_version FROM updated""",
            listed, memberId, expectedVersion, actorAccountId, actorAccountId,
        )?.get(0, Long::class.java)
        if (version != null) return Replaced.Updated(version)
        if (!ClubCapabilityAccess(database).has(actorAccountId, Capability.EDIT_MEMBERS)) return Replaced.Denied
        val active = database.fetchOne("SELECT 1 FROM members WHERE id = ? AND archived_at IS NULL", memberId) != null
        return if (active) Replaced.Changed else Replaced.NotFound
    }
}
