package org.openclub.api.members

import org.jooq.DSLContext
import java.util.UUID

/**
 * Installation-local, live database permission check. Use only an account ID
 * from a verified session; never trust a caller-supplied actor or capability.
 * Team-scoped authorizations require the future team schema (#75).
 */
internal class ClubCapabilityAccess(private val database: DSLContext) {
    enum class Capability { VIEW_MEMBER_DIRECTORY, EDIT_MEMBERS, MANAGE_PERMISSIONS }

    fun has(accountId: UUID, capability: Capability): Boolean =
        database.fetchOne(
            """SELECT 1 FROM club_capability_grants g
               JOIN members actor ON actor.id = g.member_id
               JOIN clubs club ON TRUE
               WHERE actor.account_id = ? AND g.capability = ?
                 AND (? <> 'MANAGE_PERMISSIONS' OR actor.role = 'ADMIN')
               LIMIT 1""",
            accountId, capability.name, capability.name,
        ) != null
}
