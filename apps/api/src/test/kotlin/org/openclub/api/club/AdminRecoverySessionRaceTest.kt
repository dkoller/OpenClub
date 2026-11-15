package org.openclub.api.club

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

class AdminRecoverySessionRaceTest {
    @Test
    fun recoveryWaitsForInFlightReplacementLoginThenRevokesItsSession() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 3
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val club = UUID.randomUUID()
            val admin = UUID.randomUUID()
            val old = UUID.randomUUID()
            val replacement = UUID.randomUUID()
            val input = AdminRecovery.Input(club, admin, old, replacement)
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "recovery-session-race-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", old, replacement)
                for (account in listOf(old, replacement)) {
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), account, account.toString())
                }
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                    VALUES (?, ?, 'Synthetic', 'Admin', DATE '1990-01-01',
                    'admin@example.invalid', '+41000000000', 'ADMIN')""", admin, old)

                source.connection.use { login ->
                    login.autoCommit = false
                    try {
                        // A session INSERT holds KEY SHARE on its referenced account
                        // until commit; recovery must acquire an incompatible UPDATE lock.
                        DSL.using(login, SQLDialect.POSTGRES).execute(
                            """INSERT INTO auth_sessions
                               (session_hash, account_id, csrf_token,
                                idle_expires_at, absolute_expires_at)
                               VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '10 minutes',
                                       CURRENT_TIMESTAMP + INTERVAL '1 hour')""",
                            "c".repeat(64), replacement, "x".repeat(43))
                        source.connection.use { recovering ->
                            val attempt = DSL.using(recovering, SQLDialect.POSTGRES)
                            attempt.execute("SET lock_timeout = '250ms'")
                            try {
                                val error = assertThrows(DataAccessException::class.java) {
                                    AdminRecovery(attempt).rebind(input)
                                }
                                assertEquals("55P03", error.sqlState())
                            } finally {
                                attempt.execute("RESET lock_timeout")
                            }
                        }
                        assertEquals(old, db.fetchOne(
                            "SELECT account_id FROM members WHERE id = ?", admin
                        )?.get("account_id", UUID::class.java))
                        login.commit()
                    } finally {
                        login.rollback()
                    }
                }
                AdminRecovery(db).rebind(input)
                assertEquals(replacement, db.fetchOne(
                    "SELECT account_id FROM members WHERE id = ?", admin
                )?.get("account_id", UUID::class.java))
                assertEquals(0L, db.fetchValue(
                    "SELECT COUNT(*) FROM auth_sessions WHERE account_id = ?", replacement))
            } finally {
                db.execute("DELETE FROM members WHERE id = ?", admin)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", old, replacement)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
