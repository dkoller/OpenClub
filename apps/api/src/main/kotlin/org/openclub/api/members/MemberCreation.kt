package org.openclub.api.members

import org.jooq.DSLContext
import java.time.LocalDate
import java.util.UUID

/**
 * Internal-only accountless member creation. The actor account ID must come
 * from a verified session, never the HTTP request. Do not expose this operation
 * until R03 field policies and the R04 write contract are approved.
 */
internal class MemberCreation(private val database: DSLContext) {
    data class Input(
        val firstName: String,
        val lastName: String,
        val dateOfBirth: LocalDate,
        val email: String? = null,
        val phone: String? = null,
        val gender: String? = null,
        val isPlayer: Boolean = false,
    )

    fun create(actorAccountId: UUID, input: Input): UUID? {
        val firstName = validName(input.firstName)
        val lastName = validName(input.lastName)
        require(input.dateOfBirth.isAfter(LocalDate.of(1850, 1, 1))) { "Invalid birth date" }
        require(!input.dateOfBirth.isAfter(LocalDate.now())) { "Birth date cannot be in the future" }
        val email = input.email?.trim()?.takeIf(String::isNotEmpty)
        val phone = input.phone?.trim()?.takeIf(String::isNotEmpty)
        val gender = input.gender?.trim()?.takeIf(String::isNotEmpty)
        require(email == null || email.length in 3..320) { "Invalid email length" }
        require(phone == null || phone.length <= 40) { "Invalid phone length" }
        require(gender == null || gender.length <= 200) { "Invalid gender length" }

        val newId = UUID.randomUUID()
        val created = database.execute(
            """INSERT INTO members
               (id, first_name, last_name, date_of_birth, email, phone, gender, is_player)
               SELECT ?, ?, ?, ?, ?, ?, ?, ?
               WHERE EXISTS (
                   SELECT 1 FROM members actor
                   JOIN club_capability_grants g ON g.member_id = actor.id
                   JOIN clubs club ON TRUE
                   WHERE actor.account_id = ? AND g.capability = 'EDIT_MEMBERS'
               )""",
            newId, firstName, lastName, input.dateOfBirth,
            email, phone, gender, input.isPlayer, actorAccountId,
        )
        return if (created == 1) newId else null
    }

    private fun validName(value: String): String = value.trim().also {
        require(it.length in 1..200) { "Invalid member name" }
    }
}
