package org.openclub.api.members

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.database.AuditEvents
import java.util.UUID

/**
 * Permanent erasure of an **archived** member (privacy proposal section 4, owner-approved: hard delete after
 * archive). Archiving already cleared the member's grants and team assignments and only unlinked ordinary
 * members can be archived, so the row has no dependents and one `DELETE` removes everything stored about
 * the person. An identifier-only `MEMBER_ERASED` audit event is written in the same transaction.
 *
 * Active members, linked members and administrators are never erasable: they must be unlinked and archived
 * first, which keeps the last-administrator and recovery protections intact. The caller must repeat the
 * member's full name as a typed confirmation. Every refusal is the same [Result.DENIED], so a caller learns
 * nothing about members they may not erase.
 */
internal class MemberErasure(private val database: DSLContext) {
    enum class Result { ERASED, DENIED }

    /** Interactive path: the session-derived actor needs a current verified EDIT_MEMBERS grant. */
    fun erase(actorAccountId: UUID, memberId: UUID, confirmation: String): Result =
        run(actorAccountId, memberId, confirmation)

    /** Operator command path: no browser-session actor, so the audit event has none. */
    fun eraseAsOperator(memberId: UUID, confirmation: String): Result = run(null, memberId, confirmation)

    private fun run(actorAccountId: UUID?, memberId: UUID, confirmation: String): Result =
        database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            if (actorAccountId != null) {
                // Lock the actor and their grant so a concurrent revocation cannot be outrun.
                val authorized = db.fetchOne(
                    """SELECT 1 FROM members actor
                       JOIN club_capability_grants g ON g.member_id = actor.id
                       WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")}
                         AND g.capability = 'EDIT_MEMBERS'
                       FOR SHARE OF actor, g""",
                    actorAccountId,
                ) != null
                if (!authorized) return@transactionResult Result.DENIED
            }
            // The row lock also serializes with a concurrent reactivation of the same member.
            val member = db.fetchOne(
                "SELECT first_name, last_name FROM members WHERE id = ? AND archived_at IS NOT NULL FOR UPDATE",
                memberId,
            ) ?: return@transactionResult Result.DENIED
            val expected = member.get("first_name", String::class.java).trim() + " " +
                member.get("last_name", String::class.java).trim()
            if (confirmation.trim() != expected) return@transactionResult Result.DENIED
            check(db.execute("DELETE FROM members WHERE id = ? AND archived_at IS NOT NULL", memberId) == 1) {
                "Archived member changed while the erasure lock was held"
            }
            AuditEvents.record(db, AuditEvents.Type.MEMBER_ERASED, actorAccountId, memberId)
            Result.ERASED
        }
}
