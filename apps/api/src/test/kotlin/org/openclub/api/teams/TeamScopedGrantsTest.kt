package org.openclub.api.teams

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openclub.api.members.ClubCapabilityAccess
import java.util.UUID

class TeamScopedGrantsTest {
    @Test
    fun scopedGrantRestrictsTeamReadsAndAssignmentsAndRevokesImmediately() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val club = UUID.randomUUID()
            val coachAccount = UUID.randomUUID()
            val outsider = UUID.randomUUID()
            val coach = UUID.randomUUID()
            val player = UUID.randomUUID()
            val juniors = UUID.randomUUID()
            val adults = UUID.randomUUID()
            val access = ClubCapabilityAccess(db)
            val ops = TeamAssignments(db)
            val view = ClubCapabilityAccess.Capability.VIEW_TEAMS
            val manage = ClubCapabilityAccess.Capability.MANAGE_TEAMS
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "scoped-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", coachAccount, outsider)
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, ?, 'Coach', 'Example', DATE '1990-01-01',
                    'coach@example.invalid', '+41000000000')""", coach, coachAccount)
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, 'Player', 'Example', DATE '1990-01-01',
                    'player@example.invalid', '+41000000000')""", player)
                db.execute("INSERT INTO teams (id, name) VALUES (?, 'Juniors'), (?, 'Adults')",
                    juniors, adults)

                assertFalse(access.hasForTeam(coachAccount, juniors, view))
                assertFalse(access.hasForTeam(coachAccount, juniors, manage))
                assertFalse(access.hasForTeam(outsider, juniors, view))
                assertThrows(IllegalArgumentException::class.java) {
                    access.hasForTeam(coachAccount, juniors,
                        ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS)
                }
                db.execute("""INSERT INTO team_capability_grants
                    (team_id, member_id, capability) VALUES (?, ?, 'VIEW_TEAMS')""", juniors, coach)
                assertTrue(access.hasForTeam(coachAccount, juniors, view))
                assertFalse(access.hasForTeam(coachAccount, juniors, manage))
                assertFalse(access.hasForTeam(coachAccount, adults, view))
                assertEquals(listOf(juniors), ops.list(coachAccount).map { it.id })
                assertFalse(ops.assign(coachAccount, juniors, player))

                db.execute("""INSERT INTO team_capability_grants
                    (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')""", juniors, coach)
                assertTrue(access.hasForTeam(coachAccount, juniors, manage))
                // Scope-specific management does not grant club-wide team authority.
                assertFalse(access.has(coachAccount, manage))
                assertFalse(access.has(coachAccount, view))
                assertFalse(access.hasForTeam(coachAccount, adults, manage))
                assertTrue(ops.assign(coachAccount, juniors, player))
                assertFalse(ops.assign(coachAccount, adults, player))
                assertFalse(ops.unassign(coachAccount, adults, player))
                assertFalse(ops.rename(coachAccount, juniors, "Renamed"))
                assertFalse(ops.remove(coachAccount, juniors))
                assertNull(ops.create(coachAccount, "New team"))
                assertEquals(1, ops.list(coachAccount).single().memberCount)

                assertThrows(DataAccessException::class.java) {
                    db.execute("""INSERT INTO team_capability_grants
                        (team_id, member_id, capability)
                        VALUES (?, ?, 'MANAGE_PERMISSIONS')""", juniors, coach)
                }
                assertThrows(DataAccessException::class.java) {
                    db.execute("""INSERT INTO team_capability_grants
                        (team_id, member_id, capability)
                        VALUES (?, ?, 'MANAGE_TEAMS')""", juniors, coach)
                }
                assertThrows(DataAccessException::class.java) {
                    db.execute("""INSERT INTO team_capability_grants
                        (team_id, member_id, capability)
                        VALUES (?, ?, 'VIEW_TEAMS')""", UUID.randomUUID(), coach)
                }

                db.execute("DELETE FROM team_capability_grants WHERE team_id = ?", juniors)
                assertFalse(access.hasForTeam(coachAccount, juniors, view))
                assertFalse(ops.unassign(coachAccount, juniors, player))
                assertTrue(ops.list(coachAccount).isEmpty())

                // Club-wide read authority must not imply any team mutation authority.
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_TEAMS')", coach)
                assertTrue(access.hasForTeam(coachAccount, juniors, view))
                assertTrue(access.hasForTeam(coachAccount, adults, view))
                assertFalse(access.hasForTeam(coachAccount, juniors, manage))
                assertEquals(2, ops.list(coachAccount).size)
                assertFalse(ops.assign(coachAccount, juniors, player))
                db.execute("""DELETE FROM club_capability_grants
                    WHERE member_id = ? AND capability = 'VIEW_TEAMS'""", coach)
                assertTrue(ops.list(coachAccount).isEmpty())

                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_TEAMS')", coach)
                assertEquals(2, ops.list(coachAccount).size)
                assertTrue(ops.unassign(coachAccount, juniors, player))
                db.execute("DELETE FROM club_capability_grants WHERE member_id = ?", coach)
                assertFalse(access.hasForTeam(coachAccount, juniors, manage))
                db.execute("""INSERT INTO team_capability_grants
                    (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')""", adults, coach)
                db.execute("UPDATE members SET account_id = NULL WHERE id = ?", coach)
                assertFalse(access.hasForTeam(coachAccount, adults, manage))
                assertFalse(ops.assign(coachAccount, adults, player))
                assertTrue(ops.list(coachAccount).isEmpty())
                // Link restored: the stored scoped grant is active until the member is deleted.
                db.execute("UPDATE members SET account_id = ? WHERE id = ?", coachAccount, coach)
                assertTrue(access.hasForTeam(coachAccount, adults, manage))
                // Deleting the local member must cascade the grant immediately.
                db.execute("DELETE FROM members WHERE id = ?", coach)
                assertEquals(0, db.fetchValue(
                    "SELECT COUNT(*) FROM team_capability_grants WHERE member_id = ?", coach))
                assertFalse(access.hasForTeam(coachAccount, adults, manage))
                db.execute("DELETE FROM teams WHERE id = ?", adults)
                assertEquals(0, db.fetchOne(
                    "SELECT COUNT(*) AS total FROM team_capability_grants"
                )?.get("total", Int::class.java))
            } finally {
                db.execute("DELETE FROM teams WHERE id IN (?, ?)", juniors, adults)
                db.execute("DELETE FROM members WHERE id IN (?, ?)", coach, player)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", coachAccount, outsider)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
