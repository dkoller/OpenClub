package org.openclub.api.members

import org.jooq.DSLContext
import java.time.LocalDate
import java.util.UUID
import org.openclub.api.members.ClubCapabilityAccess.Capability

/**
 * Member field updates: session-derived actor, explicit EDIT_MEMBERS grant and
 * current verified identity are checked inside the modifying SQL statement.
 * Full private-field updates remain internal until R03/R04 approval.
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
            """UPDATE members SET first_name = ?, last_name = ?
               WHERE id = ? AND archived_at IS NULL AND ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}""",
            first, last, memberId, actorAccountId,
        ) == 1
    }

    fun update(actorAccountId: UUID, memberId: UUID, input: Input): Boolean {
        val first = MemberFieldValidation.name(input.firstName)
        val last = MemberFieldValidation.name(input.lastName)
        val birthDate = MemberFieldValidation.birthDate(input.dateOfBirth)
        val email = MemberFieldValidation.email(input.email)
        val phone = MemberFieldValidation.phone(input.phone)
        val gender = MemberFieldValidation.gender(input.gender)

        return database.execute(
            """UPDATE members SET first_name = ?, last_name = ?, date_of_birth = ?,
                  email = ?, phone = ?, gender = ?, is_player = ?
               WHERE id = ? AND archived_at IS NULL AND ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}""",
            first, last, birthDate, email, phone, gender,
            input.isPlayer, memberId, actorAccountId,
        ) == 1
    }
}
