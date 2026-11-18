package org.openclub.api.members

import org.jooq.impl.DSL
import org.jooq.DSLContext
import org.openclub.api.database.AdvisoryLocks
import org.openclub.api.database.AuditEvents
import java.util.UUID

/**
 * Unlinking a member from their sign-in account (invitation design; owner-approved 2026-11-18): needed before
 * archiving and for a lost provider login, after which the member can be invited again. It changes who can sign
 * in, so it needs a live club-wide MANAGE_PERMISSIONS grant held by an ADMIN with a verified identity, and it
 * serializes with every other privilege change under the permission-mutation advisory lock. Administrators are
 * never unlinked here: that is the operator recovery procedure.
 */
internal class MemberAccountLink(private val database: DSLContext) {
    enum class Result { UNLINKED, DENIED, NOT_FOUND, CONFLICT }

    fun unlink(actorAccountId: UUID, memberId: UUID): Result = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        db.fetchValue(AdvisoryLocks.PERMISSION_MUTATION)
        val authorized = db.fetchOne(
            """SELECT 1 FROM members actor
               JOIN club_capability_grants g ON g.member_id = actor.id
               WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")} AND actor.role = 'ADMIN'
                 AND g.capability = 'MANAGE_PERMISSIONS'
               FOR SHARE OF actor, g""", actorAccountId) != null
        if (!authorized) return@transactionResult Result.DENIED
        val target = db.fetchOne("SELECT role, account_id FROM members WHERE id = ? FOR UPDATE", memberId)
            ?: return@transactionResult Result.NOT_FOUND
        val account = target.get("account_id", UUID::class.java)
        if (account == null || target.get("role", String::class.java) != "MEMBER") return@transactionResult Result.CONFLICT
        // The account can no longer sign in as this member: end its sessions and clear the member's rights.
        db.execute("DELETE FROM auth_sessions WHERE account_id = ?", account)
        db.execute("DELETE FROM team_capability_grants WHERE member_id = ?", memberId)
        db.execute("DELETE FROM club_capability_grants WHERE member_id = ?", memberId)
        check(db.execute("UPDATE members SET account_id = NULL WHERE id = ? AND account_id = ?", memberId, account) == 1) {
            "Member changed while the unlink locks were held"
        }
        AuditEvents.record(db, AuditEvents.Type.ACCOUNT_UNLINKED, actorAccountId, memberId)
        Result.UNLINKED
    }
}
