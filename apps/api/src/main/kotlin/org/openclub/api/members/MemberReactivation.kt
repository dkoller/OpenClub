package org.openclub.api.members

import org.jooq.DSLContext
import java.util.UUID
import org.openclub.api.members.ClubCapabilityAccess.Capability

/**
 * Reactivation of an archived, unlinked ordinary member (`POST /api/v1/members/{id}/reactivate`, OpenAPI 1.4.0,
 * owner-approved 2026-11-19). No historical grants, team assignments or login bindings are recreated. The
 * member-field trigger re-validates the record against the club's current adult-contact and player-gender
 * policy, so a record that no longer satisfies it is rejected. Audited as `MEMBER_REACTIVATED` in the same
 * statement. Synthetic data only until M2 approval.
 */
internal class MemberReactivation(private val database: DSLContext) {
    fun reactivate(actorAccountId: UUID, memberId: UUID): Boolean =
        database.execute(
            """WITH reactivated AS (
                 UPDATE members SET archived_at = NULL
                 WHERE id = ? AND archived_at IS NOT NULL
                   AND role = 'MEMBER' AND account_id IS NULL
                   AND ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}
                 RETURNING id)
               INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
               SELECT 'MEMBER_REACTIVATED', ?, id FROM reactivated""",
            memberId, actorAccountId, actorAccountId,
        ) == 1
}
