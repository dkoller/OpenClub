package org.openclub.api.members

import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * Internal-only role changes. Do not expose over HTTP before the member API
 * contract, privacy review, and trusted session/CSRF boundary are approved.
 */
internal class MemberRoles(private val database: DSLContext) {
    enum class Role { ADMIN, MEMBER }
    enum class Change { UPDATED, UNCHANGED, DENIED }

    fun change(actorAccountId: UUID, targetMemberId: UUID, role: Role): Change =
        database.transactionResult { transaction ->
            val db = DSL.using(transaction)
            // Shared with CapabilityGrants: grant and role mutations cannot
            // race one another into removing the final permission manager.
            db.fetchValue("SELECT pg_advisory_xact_lock(672018, 3)")
            val actor = db.fetchOne(
                """SELECT m.id FROM members m
                   JOIN club_capability_grants g ON g.member_id = m.id
                   WHERE m.account_id = ? AND m.role = 'ADMIN'
                     AND g.capability = 'MANAGE_PERMISSIONS'""",
                actorAccountId,
            )?.get("id", UUID::class.java) ?: return@transactionResult Change.DENIED
            // Even a permission manager cannot demote or promote themself.
            if (actor == targetMemberId) return@transactionResult Change.DENIED

            val existing = db.fetchOne("SELECT role FROM members WHERE id = ?", targetMemberId)
                ?.get("role", String::class.java) ?: return@transactionResult Change.DENIED
            if (existing == role.name) return@transactionResult Change.UNCHANGED

            if (existing == Role.ADMIN.name) {
                val admins = db.fetchValue(
                    "SELECT COUNT(*) FROM members WHERE role = 'ADMIN'",
                )?.toString()?.toLongOrNull() ?: error("Cannot count administrators")
                val managers = db.fetchValue(
                    """SELECT COUNT(*) FROM members m
                       JOIN club_capability_grants g ON g.member_id = m.id
                       WHERE m.role = 'ADMIN' AND g.capability = 'MANAGE_PERMISSIONS'""",
                )?.toString()?.toLongOrNull() ?: error("Cannot count permission managers")
                val targetManagesPermissions = db.fetchOne(
                    """SELECT 1 FROM club_capability_grants
                       WHERE member_id = ? AND capability = 'MANAGE_PERMISSIONS'""",
                    targetMemberId,
                ) != null
                if (admins <= 1 || (targetManagesPermissions && managers <= 1)) {
                    return@transactionResult Change.DENIED
                }
            }
            db.execute(
                "UPDATE members SET role = ? WHERE id = ?",
                role.name, targetMemberId,
            )
            Change.UPDATED
        }
}
