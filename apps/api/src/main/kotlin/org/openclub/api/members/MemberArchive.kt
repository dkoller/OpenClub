package org.openclub.api.members

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.database.AdvisoryLocks
import java.util.UUID

/**
 * Internal-only reversible data-retention boundary: hide an unlinked MEMBER
 * from active workflows, without deleting personal data. A guarded HTTP route exists.
 * Operator/legal retention approval remains necessary before real use.
 */
internal class MemberArchive(private val database: DSLContext) {
    fun archive(actorAccountId: UUID, targetMemberId: UUID): Boolean =
        database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            // Archiving revokes grants; coordinate with other privilege mutations.
            db.fetchValue(AdvisoryLocks.PERMISSION_MUTATION)
            // Lock the actor and their grant first, including against direct
            // SQL revocation, so denied requests never delete dependencies.
            val authorized = db.fetchOne(
                """SELECT 1 FROM members actor
                   JOIN club_capability_grants g ON g.member_id = actor.id
                   WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")}
                     AND g.capability = 'EDIT_MEMBERS'
                   FOR SHARE OF actor, g""",
                actorAccountId,
            ) != null
            if (!authorized) return@transactionResult false

            // Lock the member row before cleaning up dependencies. Grant and
            // assignment inserts take a sharing lock on this same row.
            val eligible = db.fetchOne(
                """SELECT id FROM members WHERE id = ? AND role = 'MEMBER'
                   AND account_id IS NULL AND archived_at IS NULL FOR UPDATE""",
                targetMemberId,
            ) != null
            if (!eligible) return@transactionResult false

            // The actor and target locks hold the authorization boundary
            // stable until the transaction commits.
            db.execute("DELETE FROM team_capability_grants WHERE member_id = ?", targetMemberId)
            db.execute("DELETE FROM club_capability_grants WHERE member_id = ?", targetMemberId)
            db.execute("DELETE FROM member_team_assignments WHERE member_id = ?", targetMemberId)
            check(db.execute(
                """UPDATE members SET archived_at = CURRENT_TIMESTAMP
                   WHERE id = ? AND role = 'MEMBER' AND account_id IS NULL
                     AND archived_at IS NULL""",
                targetMemberId,
            ) == 1) { "Member changed while archival locks were held" }
            true
        }
}
