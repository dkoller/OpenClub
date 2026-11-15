package org.openclub.api.members

import org.jooq.DSLContext
import java.util.UUID

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
                 AND EXISTS (
                   SELECT 1 FROM members actor
                   JOIN club_capability_grants g ON g.member_id = actor.id
                   WHERE actor.account_id = ? AND actor.archived_at IS NULL
                     AND g.capability = 'EDIT_MEMBERS'
                 )""",
            memberId, actorAccountId,
        ) == 1
}
