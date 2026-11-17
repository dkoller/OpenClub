package org.openclub.api.members

import org.openclub.api.members.ClubCapabilityAccess.Capability

/**
 * The single definition of the SQL actor-authorization predicate.
 *
 * Every protected query resolves the actor from a verified-session account ID and
 * requires, inside the same SQL statement as the read or write, that the actor is
 * an active (not archived) member whose account still has a verified OIDC identity.
 * Keep that rule here so a new revocation condition changes one place instead of
 * drifting across copies. Embedding these fragments preserves atomic authorization:
 * never replace them with a separate check followed by an unguarded write.
 *
 * Fragments interpolate only compile-time table aliases and [Capability] enum names,
 * never request input. The actor account ID is always a `?` bind parameter.
 */
internal object ActorSql {
    private val alias = Regex("[a-z_]+")

    /** `<member>` is an active member with a current verified identity. Binds nothing. */
    fun activeVerified(member: String): String {
        require(alias.matches(member)) { "Invalid SQL alias" }
        return """$member.archived_at IS NULL
            AND EXISTS (SELECT 1 FROM account_identities identity
                        WHERE identity.account_id = $member.account_id)"""
    }

    /**
     * EXISTS predicate: the installation's club exists and the actor (one `?` bind:
     * actor account ID) is active, verified and holds the club-wide [capability].
     * [requireAdmin] additionally requires the ADMIN role (club settings writes).
     */
    fun holdsClubCapability(capability: Capability, requireAdmin: Boolean = false): String =
        """EXISTS (
            SELECT 1 FROM members actor
            JOIN club_capability_grants g ON g.member_id = actor.id
            JOIN clubs club ON TRUE
            WHERE actor.account_id = ? AND ${activeVerified("actor")}
              AND g.capability = '${capability.name}'${if (requireAdmin) " AND actor.role = 'ADMIN'" else ""}
        )"""

    /**
     * EXISTS predicate: the actor (first `?` bind: actor account ID) is active, verified
     * and holds one of [capabilities] club-wide or on the team identified by [team]
     * (a column reference such as `team.id`, or `?` for one more bind parameter).
     * A null [team] accepts a team-scoped grant on any team.
     */
    fun holdsTeamCapability(team: String?, vararg capabilities: Capability): String {
        require(capabilities.isNotEmpty() &&
            capabilities.all { it == Capability.VIEW_TEAMS || it == Capability.MANAGE_TEAMS }) {
            "Only team capabilities can be checked at team scope"
        }
        require(team == null || team == "?" || Regex("[a-z_]+\\.[a-z_]+").matches(team)) {
            "Invalid team reference"
        }
        val names = capabilities.joinToString(", ") { "'${it.name}'" }
        val teamFilter = if (team == null) "" else " AND g.team_id = $team"
        return """EXISTS (
            SELECT 1 FROM members actor
            WHERE actor.account_id = ? AND ${activeVerified("actor")}
              AND (
                EXISTS (SELECT 1 FROM club_capability_grants g
                        WHERE g.member_id = actor.id AND g.capability IN ($names))
                OR EXISTS (SELECT 1 FROM team_capability_grants g
                           WHERE g.member_id = actor.id$teamFilter AND g.capability IN ($names))
              )
        )"""
    }
}
