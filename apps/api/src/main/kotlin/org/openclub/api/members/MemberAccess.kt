package org.openclub.api.members

import org.jooq.DSLContext
import java.util.UUID

/**
 * Installation-local club ID lookup for a trusted, verified session account.
 * This is not a member-edit permission: writes require explicit capabilities.
 */
internal class MemberAccess(private val database: DSLContext) {
    /** Null means denied: no club or no linked local member. */
    fun authorizedClubId(accountId: UUID): UUID? =
        database.fetchOne(
            """SELECT c.id FROM clubs c
               JOIN members m ON m.account_id = ?""",
            accountId,
        )?.get("id", UUID::class.java)
}
