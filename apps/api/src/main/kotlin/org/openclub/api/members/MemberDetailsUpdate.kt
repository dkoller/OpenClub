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
        val first = validName(input.firstName)
        val last = validName(input.lastName)
        require(input.dateOfBirth.isAfter(LocalDate.of(1850, 1, 1)) &&
            !input.dateOfBirth.isAfter(LocalDate.now())) { "Invalid birth date" }
        val email = input.email?.trim()?.takeIf(String::isNotEmpty)
        val phone = input.phone?.trim()?.takeIf(String::isNotEmpty)
        val gender = input.gender?.trim()?.takeIf(String::isNotEmpty)
        require(email == null || email.length in 3..320) { "Invalid email length" }
        require(phone == null || phone.length <= 40) { "Invalid phone length" }
        require(gender == null || gender.length <= 200) { "Invalid gender length" }

        return database.execute(
            """UPDATE members SET first_name = ?, last_name = ?, date_of_birth = ?,
                  email = ?, phone = ?, gender = ?, is_player = ?
               WHERE id = ? AND EXISTS (
                   SELECT 1 FROM members actor
                   JOIN club_capability_grants g ON g.member_id = actor.id
                   JOIN clubs club ON TRUE
                   WHERE actor.account_id = ? AND g.capability = 'EDIT_MEMBERS'
               )""",
            first, last, input.dateOfBirth, email, phone, gender,
            input.isPlayer, memberId, actorAccountId,
        ) == 1
    }

    private fun validName(value: String): String = value.trim().also {
        require(it.length in 1..200) { "Invalid member name" }
    }
}
