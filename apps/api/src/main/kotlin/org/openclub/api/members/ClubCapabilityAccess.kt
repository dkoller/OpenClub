package org.openclub.api.members

import org.jooq.DSLContext
import java.util.UUID

/**
 * Installation-local, live database permission check. Use only an account ID
 * from a verified session; never trust a caller-supplied actor or capability.
 * Team-scoped checks allow only VIEW_TEAMS and MANAGE_TEAMS. Delegation
 * and member-field visibility remain separate review gates.
 */
internal class ClubCapabilityAccess(private val database: DSLContext) {
    enum class Capability { VIEW_MEMBER_DIRECTORY, EDIT_MEMBERS, MANAGE_PERMISSIONS, VIEW_TEAMS, MANAGE_TEAMS }

    fun has(accountId: UUID, capability: Capability): Boolean =
        database.fetchOne(
            """SELECT 1 FROM club_capability_grants g
               JOIN members actor ON actor.id = g.member_id
               JOIN clubs club ON TRUE
               WHERE actor.account_id = ? AND actor.archived_at IS NULL AND g.capability = ?
                 AND EXISTS (SELECT 1 FROM account_identities identity
                             WHERE identity.account_id = actor.account_id)
                 AND (? <> 'MANAGE_PERMISSIONS' OR actor.role = 'ADMIN')
               LIMIT 1""",
            accountId, capability.name, capability.name,
        ) != null

    /** Team grants never authorize club-level administration or member editing. */
    fun hasForTeam(accountId: UUID, teamId: UUID, capability: Capability): Boolean {
        require(capability == Capability.VIEW_TEAMS || capability == Capability.MANAGE_TEAMS) {
            "Only team capabilities can be checked at team scope"
        }
        return database.fetchOne(
            """SELECT 1 FROM teams team
               JOIN members actor ON actor.account_id = ?
               WHERE team.id = ? AND actor.archived_at IS NULL
                 AND EXISTS (SELECT 1 FROM account_identities identity
                             WHERE identity.account_id = actor.account_id)
                 AND (
                   EXISTS (SELECT 1 FROM club_capability_grants g
                           WHERE g.member_id = actor.id AND g.capability IN (?, 'MANAGE_TEAMS'))
                   OR EXISTS (SELECT 1 FROM team_capability_grants g
                              WHERE g.team_id = team.id AND g.member_id = actor.id
                                AND g.capability IN (?, 'MANAGE_TEAMS'))
               ) LIMIT 1""",
            accountId, teamId, capability.name, capability.name,
        ) != null
    }
}
