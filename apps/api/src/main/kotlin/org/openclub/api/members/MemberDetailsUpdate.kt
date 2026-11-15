package org.openclub.api.members

import org.jooq.DSLContext
import java.time.LocalDate
import java.util.UUID

/**
 * Internal-only update of editable member details, never account/role/grants.
 * actorAccountId must come from a verified session; no HTTP route exists.
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
               WHERE id = ? AND EXISTS (
                   SELECT 1 FROM members actor
                   JOIN club_capability_grants g ON g.member_id = actor.id
                   JOIN clubs club ON TRUE
                   WHERE actor.account_id = ? AND g.capability = 'EDIT_MEMBERS'
               )""",
            first, last, birthDate, email, phone, gender,
            input.isPlayer, memberId, actorAccountId,
        ) == 1
    }

}
