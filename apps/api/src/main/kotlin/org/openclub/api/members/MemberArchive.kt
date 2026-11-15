package org.openclub.api.members

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.database.AdvisoryLocks
import java.util.UUID

/**
 * Internal-only reversible data-retention boundary: hide an unlinked MEMBER
 * from active workflows, without deleting personal data. No HTTP route.
 * Operator/legal retention approval remains necessary before real use.
 */
internal class MemberArchive(private val database: DSLContext) {
    fun archive(actorAccountId: UUID, targetMemberId: UUID): Boolean =
        database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            // Archiving revokes grants; coordinate with other privilege mutations.
            db.fetchValue(AdvisoryLocks.PERMISSION_MUTATION)
            // Lock the member row before cleaning up dependencies. Grant and
            // assignment inserts take a sharing lock on this same row.
            val eligible = db.fetchOne(
                """SELECT id FROM members WHERE id = ? AND role = 'MEMBER'
                   AND account_id IS NULL AND archived_at IS NULL FOR UPDATE""",
                targetMemberId,
            ) != null
            if (!eligible) return@transactionResult false

            // Both removal and archival roll back if the actor lost edit rights.
            db.execute("DELETE FROM team_capability_grants WHERE member_id = ?", targetMemberId)
            db.execute("DELETE FROM club_capability_grants WHERE member_id = ?", targetMemberId)
            db.execute("DELETE FROM member_team_assignments WHERE member_id = ?", targetMemberId)
            val archived = db.execute(
                """UPDATE members SET archived_at = CURRENT_TIMESTAMP
                   WHERE id = ? AND role = 'MEMBER' AND account_id IS NULL
                     AND archived_at IS NULL
                     AND EXISTS (
                       SELECT 1 FROM members actor
                       JOIN club_capability_grants grants ON grants.member_id = actor.id
                       WHERE actor.account_id = ? AND actor.archived_at IS NULL
                         AND grants.capability = 'EDIT_MEMBERS'
                     )""",
                targetMemberId, actorAccountId,
            ) == 1
            if (!archived) return@transactionResult false

            true
        }
}
