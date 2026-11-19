package org.openclub.api.club

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class FirstClubBootstrapTest {
    @Test
    fun verifiedAccountBecomesOnlyFirstAdminAtomically() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val account = UUID.randomUUID()
            val other = UUID.randomUUID()
            val identity = UUID.randomUUID()
            var created: UUID? = null
            val slug = "pilot-" + UUID.randomUUID()
            val input = FirstClubBootstrap.Input(
                account, "Synthetic pilot", slug, "Sample", "Admin",
                LocalDate.of(1990, 1, 1), "sample@example.invalid", "+41000000000",
            )
            try {
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", account, other)
                val bootstrap = FirstClubBootstrap(db)
                assertThrows(IllegalStateException::class.java) { bootstrap.create(input) }
                assertEquals(0, db.fetchCount(DSL.table("clubs")))
                db.execute(
                    "INSERT INTO account_identities (id, account_id, issuer, subject) VALUES (?, ?, ?, ?)",
                    identity, account, "https://issuer.example.invalid", "subject-" + account,
                )
                assertThrows(IllegalStateException::class.java) {
                    bootstrap.create(input.copy(accountId = other))
                }
                created = bootstrap.create(input)
                assertEquals(1, db.fetchCount(DSL.table("clubs")))
                assertEquals(1L, db.fetchValue(
                    "SELECT COUNT(*) FROM members WHERE account_id = ? AND role = 'ADMIN'",
                    account,
                ))
                assertThrows(IllegalStateException::class.java) { bootstrap.create(input) }
                // Exactly one audit event, from the one successful bootstrap: operator action, no actor.
                val adminMember = requireNotNull(db.fetchValue("SELECT id FROM members WHERE account_id = ?", account))
                val events = db.fetch(
                    "SELECT event_type, actor_account_id FROM audit_events WHERE target_member_id = ?", adminMember)
                assertEquals(1, events.size)
                assertEquals("CLUB_BOOTSTRAPPED", events[0].get(0))
                assertNull(events[0].get(1), "an operator command has no browser-session actor")
                assertThrows(DataAccessException::class.java) {
                    db.execute(
                        "INSERT INTO clubs (id, name, slug) VALUES (?, 'Second', ?)",
                        UUID.randomUUID(), "second-" + UUID.randomUUID(),
                    )
                }
                assertEquals(1, db.fetchCount(DSL.table("members")))
                assertEquals(6, db.fetchCount(DSL.table("club_capability_grants")))
                for (capability in listOf("VIEW_TEAMS", "MANAGE_TEAMS")) {
                    assertEquals(1L, db.fetchValue(
                        "SELECT COUNT(*) FROM club_capability_grants WHERE capability = ?", capability))
                }
                assertEquals(1L, db.fetchValue("""SELECT COUNT(*) FROM club_capability_grants
                    WHERE capability = 'VIEW_MEMBER_DIRECTORY'"""))
                assertEquals(1L, db.fetchValue("""SELECT COUNT(*) FROM club_capability_grants
                    WHERE capability = 'EDIT_MEMBERS'"""))
                assertEquals(1L, db.fetchValue(
                    """SELECT COUNT(*) FROM club_capability_grants g
                       JOIN members m ON m.id = g.member_id
                       WHERE m.account_id = ? AND g.capability = 'MANAGE_PERMISSIONS'""",
                    account,
                ))
                assertTrue(created != null)
            } finally {
                db.execute("DELETE FROM audit_events WHERE target_member_id IN (SELECT id FROM members WHERE account_id = ?)", account)
                db.execute("DELETE FROM members WHERE account_id = ?", account)
                if (created != null) db.execute("DELETE FROM clubs WHERE id = ?", created)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", account, other)
            }
        }
    }

    @Test
    fun invalidAdministratorDataCannotInitializeClub() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val account = UUID.randomUUID()
            val identity = UUID.randomUUID()
            val input = FirstClubBootstrap.Input(
                account, "Synthetic", "synthetic-" + UUID.randomUUID(),
                "Test", "Admin", LocalDate.of(1990, 1, 1),
                "test@example.invalid", "+41000000000",
            )
            try {
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute(
                    "INSERT INTO account_identities (id, account_id, issuer, subject) VALUES (?, ?, ?, ?)",
                    identity, account, "https://issuer.example.invalid", "subject-" + account,
                )
                assertThrows(IllegalArgumentException::class.java) {
                    FirstClubBootstrap(db).create(input.copy(slug = "../invalid"))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    FirstClubBootstrap(db).create(input.copy(email = " "))
                }
                assertEquals(0, db.fetchCount(DSL.table("clubs")))
            } finally {
                db.execute("DELETE FROM accounts WHERE id = ?", account)
            }
        }
    }
}
