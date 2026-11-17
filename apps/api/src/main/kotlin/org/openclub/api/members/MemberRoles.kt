package org.openclub.api.members

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.database.AdvisoryLocks
import java.util.UUID

/**
 * Serialized role changes shared by the verified, CSRF-protected synthetic-M1
 * HTTP route and internal callers. Production privacy/contract approval is separate.
 */
internal class MemberRoles(private val database: DSLContext) {
    enum class Role { ADMIN, MEMBER }
    enum class Change { UPDATED, UNCHANGED, DENIED }

    fun change(actorAccountId: UUID, targetMemberId: UUID, role: Role): Change =
        database.transactionResult { transaction ->
            val db = DSL.using(transaction)
            // Shared with CapabilityGrants: grant and role mutations cannot
            // race one another into removing the final permission manager.
            db.fetchValue(AdvisoryLocks.PERMISSION_MUTATION)
            val actor = db.fetchOne(
                """SELECT m.id FROM members m
                   JOIN club_capability_grants g ON g.member_id = m.id
                   WHERE m.account_id = ? AND m.role = 'ADMIN' AND ${ActorSql.activeVerified("m")}
                     AND g.capability = 'MANAGE_PERMISSIONS'""",
                actorAccountId,
            )?.get("id", UUID::class.java) ?: return@transactionResult Change.DENIED
            // Even a permission manager cannot demote or promote themself.
            if (actor == targetMemberId) return@transactionResult Change.DENIED

            val existing = db.fetchOne("SELECT role FROM members WHERE id = ? AND archived_at IS NULL", targetMemberId)
                ?.get("role", String::class.java) ?: return@transactionResult Change.DENIED
            if (existing == role.name) return@transactionResult Change.UNCHANGED

            if (existing == Role.ADMIN.name) {
                val admins = db.fetchValue(
                    "SELECT COUNT(*) FROM members WHERE role = 'ADMIN' AND archived_at IS NULL",
                )?.toString()?.toLongOrNull() ?: error("Cannot count administrators")
                val managers = db.fetchValue(
                    """SELECT COUNT(*) FROM members m
                       JOIN club_capability_grants g ON g.member_id = m.id
                       WHERE m.role = 'ADMIN' AND ${ActorSql.activeVerified("m")}
                         AND g.capability = 'MANAGE_PERMISSIONS'""",
                )?.toString()?.toLongOrNull() ?: error("Cannot count permission managers")
                val targetManagesPermissions = db.fetchOne(
                    """SELECT 1 FROM club_capability_grants g
                       JOIN members m ON m.id = g.member_id
                       WHERE g.member_id = ? AND ${ActorSql.activeVerified("m")}
                         AND g.capability = 'MANAGE_PERMISSIONS'""",
                    targetMemberId,
                ) != null
                if (admins <= 1 || (targetManagesPermissions && managers <= 1)) {
                    return@transactionResult Change.DENIED
                }
            }
            if (db.execute(
                "UPDATE members SET role = ? WHERE id = ? AND archived_at IS NULL",
                role.name, targetMemberId,
            ) != 1) return@transactionResult Change.DENIED
            // No dormant permission-manager grant may reactivate on later promotion.
            db.execute(
                """DELETE FROM club_capability_grants
                   WHERE member_id = ? AND capability = 'MANAGE_PERMISSIONS'""",
                targetMemberId,
            )
            Change.UPDATED
        }
}
