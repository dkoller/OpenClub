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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TeamAssignmentsTest {
    @Test
    fun teamsAreLocalAndEveryOperationChecksExplicitCapability() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 4
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val club = UUID.randomUUID()
            val manager = UUID.randomUUID()
            val viewer = UUID.randomUUID()
            val outsider = UUID.randomUUID()
            val managerMember = UUID.randomUUID()
            val viewerMember = UUID.randomUUID()
            val child = UUID.randomUUID()
            val second = UUID.randomUUID()
            var team: UUID? = null
            var otherTeam: UUID? = null
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "team-" + club)
                for (account in listOf(manager, viewer, outsider)) {
                    db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                }
                for ((id, account, first) in listOf(
                    Triple(managerMember, manager, "Manager"),
                    Triple(viewerMember, viewer, "Viewer"),
                    Triple(child, null, "Child"),
                    Triple(second, null, "Second"),
                )) {
                    db.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name,
                           date_of_birth, email, phone) VALUES
                           (?, ?, ?, 'Synthetic', DATE '1990-01-01',
                            'synthetic@example.invalid', '+41000000000')""",
                        id, account, first,
                    )
                }
                val operations = TeamAssignments(db)
                assertNull(operations.create(manager, "Denied"))
                assertNull(operations.create(outsider, "Denied"))
                assertTrue(operations.list(manager).isEmpty())
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_TEAMS')", managerMember)
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_TEAMS')", viewerMember)
                team = operations.create(manager, "Juniors")
                otherTeam = operations.create(manager, "Adults")
                assertNotNull(team)
                assertNotNull(otherTeam)
                assertThrows(DataAccessException::class.java) {
                    operations.create(manager, "juniors")
                }
                // Direct SQL bypassing the service's trim must not allow a second team name.
                assertThrows(DataAccessException::class.java) {
                    db.execute("INSERT INTO teams (id, name) VALUES (?, '  jUnIoRs  ')",
                        UUID.randomUUID())
                }
                assertThrows(DataAccessException::class.java) {
                    db.execute("UPDATE teams SET name = ' JUNIORS ' WHERE id = ?", otherTeam)
                }
                assertEquals("Adults", db.fetchValue(
                    "SELECT name FROM teams WHERE id = ?", otherTeam))

                assertThrows(IllegalArgumentException::class.java) {
                    operations.create(manager, " ")
                }
                assertEquals(2, operations.list(manager).size)
                assertEquals(2, operations.list(viewer).size)
                assertTrue(operations.list(outsider).isEmpty())
                assertFalse(operations.rename(viewer, team!!, "Forbidden"))
                assertFalse(operations.assign(viewer, team!!, child))
                assertFalse(operations.assign(outsider, team!!, child))
                assertTrue(operations.assign(manager, team!!, child))
                assertFalse(operations.assign(manager, team!!, child))
                assertTrue(operations.assign(manager, otherTeam!!, child))
                assertTrue(operations.assign(manager, team!!, second))
                assertFalse(operations.assign(manager, UUID.randomUUID(), child))
                assertFalse(operations.assign(manager, team!!, UUID.randomUUID()))
                assertEquals(2, operations.list(viewer).first { it.id == team }.memberCount)
                assertEquals(1, operations.list(viewer).first { it.id == otherTeam }.memberCount)
                assertTrue(operations.rename(manager, team!!, "Falcons Juniors"))
                assertTrue(operations.unassign(manager, team!!, child))
                assertFalse(operations.unassign(manager, team!!, child))
                db.execute("DELETE FROM club_capability_grants WHERE member_id = ?",
                    managerMember)
                assertFalse(operations.rename(manager, team!!, "Denied"))
                assertFalse(operations.unassign(manager, otherTeam!!, child))
                assertFalse(operations.remove(manager, team!!))
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_TEAMS')", managerMember)
                assertTrue(operations.remove(manager, otherTeam!!))
                assertEquals(0, db.fetchOne(
                    "SELECT COUNT(*) AS total FROM member_team_assignments WHERE team_id = ?",
                    otherTeam,
                )?.get("total", Int::class.java))
                // Unlinking manager immediately revokes all access.
                db.execute("UPDATE members SET account_id = NULL WHERE id = ?", managerMember)
                assertFalse(operations.assign(manager, team!!, child))
                assertTrue(operations.list(manager).isEmpty())
            } finally {
                db.execute("DELETE FROM member_team_assignments WHERE team_id IN (?, ?)", team, otherTeam)
                db.execute("DELETE FROM teams WHERE id IN (?, ?)", team, otherTeam)
                db.execute("DELETE FROM members WHERE id IN (?, ?, ?, ?)",
                    managerMember, viewerMember, child, second)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?, ?)", manager, viewer, outsider)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }

    @Test
    fun competingAssignmentInsertionsCreateExactlyOneRelationship() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 4
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val club = UUID.randomUUID()
            val account = UUID.randomUUID()
            val manager = UUID.randomUUID()
            val member = UUID.randomUUID()
            var team: UUID? = null
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "concurrent-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, ?, 'Manager', 'Test', DATE '1990-01-01',
                    'test@example.invalid', '+41000000000')""", manager, account)
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, 'Member', 'Test', DATE '1990-01-01',
                    'test@example.invalid', '+41000000000')""", member)
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_TEAMS')", manager)
                team = requireNotNull(TeamAssignments(db).create(account, "Concurrent"))
                val ready = CountDownLatch(2)
                val go = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val tasks = (1..2).map {
                        pool.submit<Boolean> {
                            ready.countDown()
                            go.await()
                            TeamAssignments(DSL.using(source, SQLDialect.POSTGRES))
                                .assign(account, requireNotNull(team), member)
                        }
                    }
                    assertTrue(ready.await(10, TimeUnit.SECONDS))
                    go.countDown()
                    assertEquals(1, tasks.count { it.get(15, TimeUnit.SECONDS) })
                    assertEquals(1, db.fetchCount(DSL.table("member_team_assignments")))
                } finally {
                    go.countDown()
                    pool.shutdownNow()
                }
            } finally {
                db.execute("DELETE FROM teams WHERE id = ?", team)
                db.execute("DELETE FROM members WHERE id IN (?, ?)", manager, member)
                db.execute("DELETE FROM accounts WHERE id = ?", account)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
