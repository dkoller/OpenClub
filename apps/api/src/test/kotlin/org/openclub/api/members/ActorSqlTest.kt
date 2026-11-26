package org.openclub.api.members

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openclub.api.members.ClubCapabilityAccess.Capability

class ActorSqlTest {
    @Test
    fun activeVerifiedRequiresArchiveAndIdentityChecksForTheGivenAlias() {
        val sql = ActorSql.activeVerified("actor")
        assertTrue("actor.archived_at IS NULL" in sql)
        assertTrue("identity.account_id = actor.account_id" in sql)
    }

    @Test
    fun rejectsNonIdentifierAliasesAndTeamReferences() {
        for (alias in listOf("", "actor;", "a.b", "actor OR TRUE", "Actor")) {
            assertThrows(IllegalArgumentException::class.java) { ActorSql.activeVerified(alias) }
        }
        for (team in listOf("", "team", "team.id OR TRUE", "'x'")) {
            assertThrows(IllegalArgumentException::class.java) {
                ActorSql.holdsTeamCapability(team, Capability.VIEW_TEAMS)
            }
        }
    }

    @Test
    fun clubGateBindsOnlyTheActorAndRequiresAdminOnlyWhenAsked() {
        val member = ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)
        assertTrue("g.capability = 'EDIT_MEMBERS'" in member)
        assertFalse("role" in member)
        assertTrue(member.count { it == '?' } == 1)
        val admin = ActorSql.holdsClubCapability(Capability.MANAGE_PERMISSIONS, requireAdmin = true)
        assertTrue("actor.role = 'ADMIN'" in admin)
    }

    @Test
    fun teamGateAcceptsOnlyTeamCapabilities() {
        assertThrows(IllegalArgumentException::class.java) {
            ActorSql.holdsTeamCapability(null, Capability.EDIT_MEMBERS)
        }
        assertThrows(IllegalArgumentException::class.java) { ActorSql.holdsTeamCapability(null) }
        val any = ActorSql.holdsTeamCapability(null, Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)
        assertFalse("team_id" in any)
        assertTrue(any.count { it == '?' } == 1)
        val bound = ActorSql.holdsTeamCapability("?", Capability.MANAGE_TEAMS)
        assertTrue(bound.count { it == '?' } == 2)
    }

    @Test
    fun clubWideCheckNeedsACapability() {
        // The database part is covered by every route's 404/403 tests; without capabilities it refuses before SQL.
        assertThrows(IllegalArgumentException::class.java) {
            ActorSql.holdsClubWide(org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES), java.util.UUID.randomUUID())
        }
    }
}
