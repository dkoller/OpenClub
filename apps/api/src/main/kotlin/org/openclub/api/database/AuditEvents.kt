package org.openclub.api.database

import org.jooq.DSLContext
import java.util.UUID

/**
 * Writer for the append-only `audit_events` trail (V12). Every call belongs inside the same
 * transaction, or the same SQL statement, as the change it records, so an event exists exactly when the
 * change committed. Events hold identifiers only: never names, birth dates, contacts or free text.
 */
internal object AuditEvents {
    enum class Type { MEMBER_CREATED, MEMBER_UPDATED, MEMBER_ARCHIVED, ROLE_CHANGED, GRANT_ADDED, GRANT_REVOKED, ADMIN_RECOVERED, CLUB_BOOTSTRAPPED, MEMBER_ERASED, MEMBER_EXPORTED,
        INVITATION_CREATED, INVITATION_ACCEPTED, INVITATION_REVOKED, ACCOUNT_UNLINKED, CONTACT_READ,
        MEMBER_REACTIVATED, GUARDIAN_LINKED, GUARDIAN_UNLINKED, DEPENDENT_READ, DEPENDENT_UPDATED }

    /** Records one event; [actorAccountId] is null for operator commands. */
    fun record(
        db: DSLContext,
        type: Type,
        actorAccountId: UUID?,
        targetMemberId: UUID?,
        capability: String? = null,
        scope: String? = null,
        teamId: UUID? = null,
        newRole: String? = null,
        relatedMemberId: UUID? = null,
    ) {
        db.execute(
            """INSERT INTO audit_events
               (event_type, actor_account_id, target_member_id, capability, scope, team_id, new_role, related_member_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            type.name, actorAccountId, targetMemberId, capability, scope, teamId, newRole, relatedMemberId,
        )
    }
}
