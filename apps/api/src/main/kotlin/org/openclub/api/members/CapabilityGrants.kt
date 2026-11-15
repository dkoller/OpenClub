package org.openclub.api.members

import org.openclub.api.database.AdvisoryLocks

import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * Internal-only grant mutation boundary, called with a verified session account.
 * Serializes every grant mutation across processes to prevent competing requests
 * from removing the last eligible permission manager.
 */
internal class CapabilityGrants(private val database: DSLContext) {
    enum class Scope { CLUB, TEAM }
    enum class Change { GRANTED, REVOKED, UNCHANGED, DENIED }

    fun grant(actorAccountId: UUID, memberId: UUID, capability: ClubCapabilityAccess.Capability,
              scope: Scope, teamId: UUID? = null): Change =
        mutate(actorAccountId, memberId, capability, scope, teamId, granting = true)

    fun revoke(actorAccountId: UUID, memberId: UUID, capability: ClubCapabilityAccess.Capability,
               scope: Scope, teamId: UUID? = null): Change =
        mutate(actorAccountId, memberId, capability, scope, teamId, granting = false)

    private fun mutate(
        actorAccountId: UUID,
        memberId: UUID,
        capability: ClubCapabilityAccess.Capability,
        scope: Scope,
        teamId: UUID?,
        granting: Boolean,
    ): Change {
        require((scope == Scope.TEAM) == (teamId != null)) { "Team ID must match scope" }
        require(scope != Scope.TEAM ||
            capability == ClubCapabilityAccess.Capability.VIEW_TEAMS ||
            capability == ClubCapabilityAccess.Capability.MANAGE_TEAMS) {
            "Capability cannot be delegated to a team"
        }
        return database.transactionResult { transaction ->
            val db = DSL.using(transaction)
            // Fixed installation-wide lock; both grants and revokes use the same order.
            db.fetchValue(AdvisoryLocks.PERMISSION_MUTATION)
            val actor = db.fetchOne(
                """SELECT actor.id FROM members actor
                   JOIN club_capability_grants g ON g.member_id = actor.id
                   WHERE actor.account_id = ? AND actor.role = 'ADMIN'
                     AND g.capability = 'MANAGE_PERMISSIONS'
                     AND EXISTS (SELECT 1 FROM account_identities ai
                                 WHERE ai.account_id = actor.account_id)""",
                actorAccountId,
            )?.get("id", UUID::class.java) ?: return@transactionResult Change.DENIED
            if (actor == memberId) return@transactionResult Change.DENIED

            val recipient = db.fetchOne(
                """SELECT m.role, EXISTS (
                       SELECT 1 FROM account_identities ai WHERE ai.account_id = m.account_id
                   ) AS verified FROM members m WHERE m.id = ?""", memberId,
            )
                ?: return@transactionResult Change.DENIED
            if (granting && capability == ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS &&
                (recipient.get("role", String::class.java) != "ADMIN" ||
                    recipient.get("verified", Boolean::class.java) != true)
            ) return@transactionResult Change.DENIED
            if (scope == Scope.TEAM &&
                db.fetchOne("SELECT 1 FROM teams WHERE id = ?", teamId) == null
            ) return@transactionResult Change.DENIED

            if (!granting && scope == Scope.CLUB &&
                capability == ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS &&
                recipient.get("role", String::class.java) == "ADMIN" &&
                recipient.get("verified", Boolean::class.java) == true &&
                db.fetchOne("""SELECT 1 FROM club_capability_grants
                    WHERE member_id = ? AND capability = 'MANAGE_PERMISSIONS'""", memberId) != null
            ) {
                val managers = db.fetchValue(
                    """SELECT COUNT(*) FROM members m
                       JOIN club_capability_grants g ON g.member_id = m.id
                       WHERE m.role = 'ADMIN' AND g.capability = 'MANAGE_PERMISSIONS'
                         AND EXISTS (SELECT 1 FROM account_identities ai
                                     WHERE ai.account_id = m.account_id)""",
                )?.toString()?.toLongOrNull() ?: error("Cannot count permission managers")
                if (managers <= 1) return@transactionResult Change.DENIED
            }

            val changed = if (scope == Scope.CLUB) {
                if (granting) db.execute(
                    """INSERT INTO club_capability_grants (member_id, capability)
                       VALUES (?, ?) ON CONFLICT DO NOTHING""", memberId, capability.name,
                ) else db.execute(
                    "DELETE FROM club_capability_grants WHERE member_id = ? AND capability = ?",
                    memberId, capability.name,
                )
            } else {
                if (granting) db.execute(
                    """INSERT INTO team_capability_grants (team_id, member_id, capability)
                       VALUES (?, ?, ?) ON CONFLICT DO NOTHING""",
                    teamId, memberId, capability.name,
                ) else db.execute(
                    """DELETE FROM team_capability_grants
                       WHERE team_id = ? AND member_id = ? AND capability = ?""",
                    teamId, memberId, capability.name,
                )
            }
            when {
                changed == 0 -> Change.UNCHANGED
                granting -> Change.GRANTED
                else -> Change.REVOKED
            }
        }
    }
}
