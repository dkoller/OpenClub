package org.openclub.api

import org.jooq.DSLContext
import java.util.UUID

/**
 * Database lookup only: never accept a personId from an HTTP request as proof of identity.
 * The future auth layer must resolve this from a verified OIDC issuer + subject.
 */
internal class ClubMembershipAccess(private val database: DSLContext) {
    internal enum class Role { ADMIN, MEMBER }

    fun roleFor(personId: UUID, clubId: UUID): Role? {
        val role = database.fetchValue(
            "SELECT role FROM memberships WHERE person_id = ? AND club_id = ?",
            personId,
            clubId,
        ) ?: return null

        return Role.valueOf(role.toString())
    }

    fun canManageMembers(personId: UUID, clubId: UUID): Boolean =
        roleFor(personId, clubId) == Role.ADMIN
}
