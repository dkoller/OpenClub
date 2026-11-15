package org.openclub.api.teams

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class TeamMemberDirectoryTest {
    @Test
    fun requiresBothDirectoryAndTeamRightsForEachBoundedPage() {
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
            val account = UUID.randomUUID()
            val outsider = UUID.randomUUID()
            val viewer = UUID.randomUUID()
            val targetA = UUID.randomUUID()
            val targetB = UUID.randomUUID()
            val firstTeam = UUID.randomUUID()
            val otherTeam = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "roster-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", account, outsider)
                db.execute(
                    """INSERT INTO members (id, account_id, first_name, last_name,
                         date_of_birth, email, phone)
                       VALUES (?, ?, 'Viewer', 'Synthetic', DATE '1990-01-01',
                         'viewer@example.invalid', '+41000000000')""",
                    viewer, account,
                )
                for ((id, name) in listOf(targetA to "Anna", targetB to "Berta")) {
                    db.execute(
                        """INSERT INTO members (id, first_name, last_name,
                             date_of_birth, email, phone)
                           VALUES (?, ?, 'Example', DATE '1990-01-01',
                             'member@example.invalid', '+41000000000')""",
                        id, name,
                    )
                }
                db.execute("INSERT INTO teams (id, name) VALUES (?, 'Alpha'), (?, 'Beta')",
                    firstTeam, otherTeam)
                db.execute("""INSERT INTO member_team_assignments (team_id, member_id)
                    VALUES (?, ?), (?, ?), (?, ?)""",
                    firstTeam, targetA, firstTeam, targetB, otherTeam, targetB)
                val roster = TeamMemberDirectory(db)
                assertTrue(roster.list(outsider, firstTeam).members.isEmpty())
                assertTrue(roster.list(account, firstTeam).members.isEmpty())
                db.execute(
                    "INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')",
                    viewer,
                )
                assertTrue(roster.list(account, firstTeam).members.isEmpty())
                db.execute(
                    "INSERT INTO team_capability_grants VALUES (?, ?, 'VIEW_TEAMS')",
                    firstTeam, viewer,
                )
                val first = roster.list(account, firstTeam, limit = 1)
                assertEquals(listOf(targetA), first.members.map { it.id })
                assertNotNull(first.next)
                assertEquals(listOf(targetB),
                    roster.list(account, firstTeam, limit = 1, after = first.next)
                        .members.map { it.id })
                assertTrue(roster.list(account, otherTeam).members.isEmpty())
                assertTrue(roster.list(account, UUID.randomUUID()).members.isEmpty())
                assertThrows(IllegalArgumentException::class.java) {
                    roster.list(account, firstTeam, limit = 51)
                }
                db.execute(
                    "DELETE FROM club_capability_grants WHERE member_id = ?",
                    viewer,
                )
                assertTrue(roster.list(account, firstTeam, limit = 1,
                    after = first.next).members.isEmpty())
                db.execute(
                    "INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')",
                    viewer,
                )
                assertEquals(listOf(targetB),
                    roster.list(account, firstTeam, limit = 1, after = first.next)
                        .members.map { it.id })
                db.execute(
                    "DELETE FROM team_capability_grants WHERE member_id = ?",
                    viewer,
                )
                assertTrue(roster.list(account, firstTeam).members.isEmpty())
                db.execute(
                    "INSERT INTO club_capability_grants VALUES (?, 'VIEW_TEAMS')",
                    viewer,
                )
                assertEquals(listOf(targetB), roster.list(account, otherTeam)
                    .members.map { it.id })
                db.execute("UPDATE members SET account_id = NULL WHERE id = ?", viewer)
                assertTrue(roster.list(account, firstTeam).members.isEmpty())
            } finally {
                db.execute("DELETE FROM teams WHERE id IN (?, ?)", firstTeam, otherTeam)
                db.execute("DELETE FROM members WHERE id IN (?, ?, ?)", viewer, targetA, targetB)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", account, outsider)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
