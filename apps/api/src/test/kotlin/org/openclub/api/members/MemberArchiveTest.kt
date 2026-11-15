package org.openclub.api.members

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openclub.api.teams.TeamAssignments
import java.time.LocalDate
import java.util.UUID

class MemberArchiveTest {
    @Test
    fun archivesOnlyUnlinkedOrdinaryMembersAndRevokesActiveAccessAtomically() {
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
            val linkedAccount = UUID.randomUUID()
            val outsider = UUID.randomUUID()
            val editor = UUID.randomUUID()
            val linkedMember = UUID.randomUUID()
            val archived = UUID.randomUUID()
            val admin = UUID.randomUUID()
            val team = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "archive-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", account, linkedAccount)
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, ?, 'Editor', 'Synthetic', DATE '1990-01-01',
                            'editor@example.invalid', '+41000000000'),
                           (?, ?, 'Linked', 'Synthetic', DATE '1990-01-01',
                            'linked@example.invalid', '+41000000000')""",
                    editor, account, linkedMember, linkedAccount)
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth, email, phone, role)
                    VALUES (?, 'Target', 'Synthetic', DATE '1990-01-01',
                            'target@example.invalid', '+41000000000', 'MEMBER'),
                           (?, 'Admin', 'Synthetic', DATE '1990-01-01',
                            'admin@example.invalid', '+41000000000', 'ADMIN')""",
                    archived, admin)
                db.execute("INSERT INTO teams (id, name) VALUES (?, 'Synthetic Team')", team)
                db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, archived)
                db.execute("""INSERT INTO club_capability_grants VALUES
                    (?, 'EDIT_MEMBERS'), (?, 'VIEW_MEMBER_DIRECTORY'),
                    (?, 'MANAGE_TEAMS'), (?, 'VIEW_TEAMS')""",
                    editor, editor, editor, archived)
                db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'VIEW_TEAMS')",
                    team, archived)

                val archive = MemberArchive(db)
                assertFalse(archive.archive(outsider, archived))
                assertFalse(archive.archive(account, linkedMember))
                assertFalse(archive.archive(account, admin))
                assertNull(db.fetchValue("SELECT archived_at FROM members WHERE id = ?", archived))
                assertTrue(archive.archive(account, archived))
                assertFalse(archive.archive(account, archived))
                assertNotNull(db.fetchValue("SELECT archived_at FROM members WHERE id = ?", archived))
                assertEquals(0, db.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id = ?", archived)?.toString()?.toInt())
                assertEquals(0, db.fetchValue(
                    "SELECT COUNT(*) FROM team_capability_grants WHERE member_id = ?", archived)?.toString()?.toInt())
                assertEquals(0, db.fetchValue(
                    "SELECT COUNT(*) FROM member_team_assignments WHERE member_id = ?", archived)?.toString()?.toInt())
                assertTrue(MemberDirectory(db).search(account, "Target").members.isEmpty())
                assertFalse(TeamAssignments(db).assign(account, team, archived))
                assertFalse(MemberDetailsUpdate(db).update(account, archived,
                    MemberDetailsUpdate.Input("Changed", "Synthetic", LocalDate.of(1990, 1, 1),
                        "changed@example.invalid", "+41000000000")))
                assertEquals("Target", db.fetchValue(
                    "SELECT first_name FROM members WHERE id = ?", archived))
                assertThrows(DataAccessException::class.java) {
                    db.execute("UPDATE members SET account_id = ? WHERE id = ?", linkedAccount, archived)
                }
                assertThrows(DataAccessException::class.java) {
                    db.execute("UPDATE members SET role = 'ADMIN' WHERE id = ?", archived)
                }
            } finally {
                db.execute("DELETE FROM teams WHERE id = ?", team)
                db.execute("DELETE FROM members WHERE id IN (?, ?, ?, ?)",
                    editor, linkedMember, archived, admin)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", account, linkedAccount)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
