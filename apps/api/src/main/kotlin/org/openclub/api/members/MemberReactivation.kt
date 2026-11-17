package org.openclub.api.members

import org.jooq.DSLContext
import java.util.UUID
import org.openclub.api.members.ClubCapabilityAccess.Capability

/**
 * Internal-only reactivation of an archived, unlinked ordinary member.
 * No historical grants, team assignments or login bindings are recreated.
 * Not approved for HTTP exposure or real personal data.
 */
internal class MemberReactivation(private val database: DSLContext) {
    fun reactivate(actorAccountId: UUID, memberId: UUID): Boolean =
        database.execute(
            """UPDATE members SET archived_at = NULL
               WHERE id = ? AND archived_at IS NOT NULL
                 AND role = 'MEMBER' AND account_id IS NULL
                 AND ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}""",
            memberId, actorAccountId,
        ) == 1
}
