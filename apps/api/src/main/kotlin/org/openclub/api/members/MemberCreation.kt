package org.openclub.api.members

import org.jooq.DSLContext
import java.time.LocalDate
import java.util.UUID
import org.openclub.api.members.ClubCapabilityAccess.Capability

/**
 * Accountless member creation from a verified browser-session account.
 * The PostgreSQL write checks the live grant and identity in one statement.
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
        val firstName = MemberFieldValidation.name(input.firstName)
        val lastName = MemberFieldValidation.name(input.lastName)
        val birthDate = MemberFieldValidation.birthDate(input.dateOfBirth)
        val email = MemberFieldValidation.email(input.email)
        val phone = MemberFieldValidation.phone(input.phone)
        val gender = MemberFieldValidation.gender(input.gender)

        val newId = UUID.randomUUID()
        val created = database.execute(
            """INSERT INTO members
               (id, first_name, last_name, date_of_birth, email, phone, gender, is_player)
               SELECT ?, ?, ?, ?, ?, ?, ?, ?
               WHERE ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}""",
            newId, firstName, lastName, birthDate,
            email, phone, gender, input.isPlayer, actorAccountId,
        )
        return if (created == 1) newId else null
    }
}
