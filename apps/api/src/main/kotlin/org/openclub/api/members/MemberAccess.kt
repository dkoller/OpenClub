package org.openclub.api.members

import org.jooq.DSLContext
import java.util.UUID

/**
 * Installation-local role lookup. Login alone never grants membership.
 * Callers must derive accountId from the verified authenticated session.
 * This is not an HTTP authorization guard.
 */
internal class MemberAccess(private val database: DSLContext) {
    internal enum class Role { ADMIN, MEMBER }

    fun roleFor(accountId: UUID): Role? {
        val role = database.fetchValue(
            "SELECT role FROM members WHERE account_id = ?", accountId,
        ) ?: return null
        return Role.valueOf(role.toString())
    }

    fun canManageMembers(accountId: UUID): Boolean = roleFor(accountId) == Role.ADMIN
}
