package org.openclub.api.members

import org.jooq.DSLContext
import java.util.UUID

/**
 * Installation-local authorization. accountId must come from a verified session,
 * never from HTTP fields. This is the interim ADMIN/MEMBER policy; granular
 * team and capability grants are deliberately deferred to #76.
 */
internal class MemberAccess(private val database: DSLContext) {
    internal enum class Role { ADMIN, MEMBER }
    internal enum class Permission { VIEW_CLUB, MANAGE_MEMBERS }

    fun roleFor(accountId: UUID): Role? {
        val role = database.fetchValue(
            "SELECT role FROM members WHERE account_id = ?", accountId,
        ) ?: return null
        return Role.valueOf(role.toString())
    }

    /** Null means denied: no club, unlinked account, or insufficient role. */
    fun authorizedClubId(accountId: UUID, permission: Permission): UUID? =
        database.fetchOne(
            """SELECT c.id FROM clubs c
               JOIN members m ON m.account_id = ?
               WHERE (? = FALSE OR m.role = 'ADMIN')""",
            accountId, permission == Permission.MANAGE_MEMBERS,
        )?.get("id", UUID::class.java)

    fun canManageMembers(accountId: UUID): Boolean =
        authorizedClubId(accountId, Permission.MANAGE_MEMBERS) != null
}
