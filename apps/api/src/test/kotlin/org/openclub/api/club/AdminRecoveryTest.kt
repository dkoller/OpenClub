package org.openclub.api.club

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.openclub.api.database.AdvisoryLocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AdminRecoveryTest {
    @Test
    fun rebindsOnlySoleAdminToVerifiedUnlinkedAccountAndRevokesOldSessions() {
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
            val admin = UUID.randomUUID()
            val old = UUID.randomUUID()
            val replacement = UUID.randomUUID()
            val unverified = UUID.randomUUID()
            val occupied = UUID.randomUUID()
            val otherMember = UUID.randomUUID()
            val secondAdmin = UUID.randomUUID()
            val sessionHash = "a".repeat(64)
            val input = AdminRecovery.Input(club, admin, old, replacement)
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "synthetic-" + club)
                for (account in listOf(old, replacement, unverified, occupied)) {
                    db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                }
                for (account in listOf(old, replacement, occupied)) {
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), account, "subject-" + account)
                }
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                    VALUES (?, ?, 'Sample', 'Admin', DATE '1990-01-01',
                    'admin@example.invalid', '+41000000000', 'ADMIN')""", admin, old)
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, ?, 'Sample', 'Member', DATE '1990-01-01',
                    'member@example.invalid', '+41000000000')""", otherMember, occupied)
                // An already-authenticated replacement must not inherit admin rights
                // merely because an operator rebinds the administrator member.
                for ((account, hash) in listOf(old to sessionHash, replacement to "b".repeat(64))) {
                    db.execute("""INSERT INTO auth_sessions
                        (session_hash, account_id, csrf_token, idle_expires_at, absolute_expires_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '10 minutes',
                        CURRENT_TIMESTAMP + INTERVAL '1 hour')""", hash, account, "x".repeat(43))
                }
                val recovery = AdminRecovery(db)
                fun linked(): UUID? = db.fetchOne(
                    "SELECT account_id FROM members WHERE id = ?", admin,
                )?.get("account_id", UUID::class.java)
                assertThrows(IllegalArgumentException::class.java) {
                    recovery.rebind(input.copy(replacementAccountId = old))
                }
                assertThrows(IllegalStateException::class.java) {
                    recovery.rebind(input.copy(clubId = UUID.randomUUID()))
                }
                assertThrows(IllegalStateException::class.java) {
                    recovery.rebind(input.copy(adminMemberId = otherMember))
                }
                assertThrows(IllegalStateException::class.java) {
                    recovery.rebind(input.copy(replacementAccountId = unverified))
                }
                assertThrows(IllegalStateException::class.java) {
                    recovery.rebind(input.copy(replacementAccountId = occupied))
                }
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth, email, phone, role)
                    VALUES (?, 'Other', 'Admin', DATE '1990-01-01',
                    'other@example.invalid', '+41000000000', 'ADMIN')""", secondAdmin)
                assertThrows(IllegalStateException::class.java) { recovery.rebind(input) }
                db.execute("DELETE FROM members WHERE id = ?", secondAdmin)
                // Recovery must not accept a replacement identity being removed by
                // a concurrent transaction before the administrator is rebound.
                source.connection.use { removingIdentity ->
                    removingIdentity.autoCommit = false
                    try {
                        DSL.using(removingIdentity, SQLDialect.POSTGRES).execute(
                            "DELETE FROM account_identities WHERE account_id = ?", replacement)
                        source.connection.use { attempting ->
                            val candidate = DSL.using(attempting, SQLDialect.POSTGRES)
                            candidate.execute("SET lock_timeout = '250ms'")
                            try {
                                val blocked = assertThrows(DataAccessException::class.java) {
                                    AdminRecovery(candidate).rebind(input)
                                }
                                assertEquals("55P03", blocked.sqlState())
                            } finally {
                                candidate.execute("RESET lock_timeout")
                            }
                        }
                    } finally {
                        removingIdentity.rollback()
                    }
                }
                assertEquals(old, linked())
                // A concurrent protected role/grant mutation holds this lock;
                // recovery must wait rather than trust a stale sole-admin count.
                source.connection.use { holding ->
                    holding.autoCommit = false
                    try {
                        DSL.using(holding, SQLDialect.POSTGRES)
                            .fetchValue(AdvisoryLocks.PERMISSION_MUTATION)
                        source.connection.use { attempting ->
                            val candidate = DSL.using(attempting, SQLDialect.POSTGRES)
                            candidate.execute("SET lock_timeout = '250ms'")
                            try {
                                val blocked = assertThrows(DataAccessException::class.java) {
                                    AdminRecovery(candidate).rebind(input)
                                }
                                assertEquals("55P03", blocked.sqlState())
                            } finally {
                                candidate.execute("RESET lock_timeout")
                            }
                        }
                    } finally {
                        holding.rollback()
                    }
                }
                assertEquals(old, linked())
                assertEquals(2, db.fetchCount(DSL.table("auth_sessions")))
                recovery.rebind(input)
                assertEquals(replacement, linked())
                assertEquals(0, db.fetchCount(DSL.table("auth_sessions")))
                assertThrows(IllegalStateException::class.java) { recovery.rebind(input) }
                assertEquals(replacement, linked())
                assertEquals(2, db.fetchCount(DSL.table("members")))
            } finally {
                db.execute("DELETE FROM auth_sessions WHERE account_id IN (?, ?)", old, replacement)
                db.execute("DELETE FROM members WHERE id IN (?, ?, ?)", admin, otherMember, secondAdmin)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?, ?, ?)", old, replacement, unverified, occupied)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
    @Test
    fun competingRecoveriesCannotBothReplaceTheSameAdministrator() {
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
            val replacements = listOf(UUID.randomUUID(), UUID.randomUUID())
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "synthetic-" + club)
                for (account in listOf(old) + replacements) {
                    db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), account, "subject-" + account)
                }
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                    VALUES (?, ?, 'Sample', 'Admin', DATE '1990-01-01',
                    'admin@example.invalid', '+41000000000', 'ADMIN')""", admin, old)
                val ready = java.util.concurrent.CountDownLatch(2)
                val start = java.util.concurrent.CountDownLatch(1)
                val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
                try {
                    val outcomes = replacements.map { replacement ->
                        executor.submit<Boolean> {
                            ready.countDown()
                            start.await()
                            try {
                                AdminRecovery(DSL.using(source, SQLDialect.POSTGRES)).rebind(
                                    AdminRecovery.Input(club, admin, old, replacement)
                                )
                                true
                            } catch (_: IllegalStateException) {
                                false
                            }
                        }
                    }
                    org.junit.jupiter.api.Assertions.assertTrue(
                        ready.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    )
                    start.countDown()
                    assertEquals(1, outcomes.count { it.get(15, java.util.concurrent.TimeUnit.SECONDS) })
                    val selected = db.fetchOne("SELECT account_id FROM members WHERE id = ?", admin)
                        ?.get("account_id", UUID::class.java)
                    org.junit.jupiter.api.Assertions.assertTrue(selected in replacements)
                } finally {
                    start.countDown()
                    executor.shutdownNow()
                }
            } finally {
                db.execute("DELETE FROM members WHERE id = ?", admin)
                for (account in listOf(old) + replacements) {
                    db.execute("DELETE FROM accounts WHERE id = ?", account)
                }
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }

}
