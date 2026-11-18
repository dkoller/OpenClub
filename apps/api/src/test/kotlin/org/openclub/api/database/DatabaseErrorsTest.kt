package org.openclub.api.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

class DatabaseErrorsTest {
    private fun failure(state: String?) = DataAccessException("x", SQLException("synthetic", state))

    @Test
    fun dataRejectionsAreClientErrorsAndEverythingElseIsAServerFault() {
        for (state in listOf("22001", "22P02", "23502", "23503", "23505", "23514", "P0001")) {
            assertEquals(true, failure(state).rejectedTheData(), state)
        }
        for (state in listOf("08003", "08006", "40001", "40P01", "42601", "42P01", "53300", "57014", "57P01",
            "0A000", "XX000", "P0002", "")) {
            assertEquals(false, failure(state).rejectedTheData(), state)
        }
        assertEquals(false, failure(null).rejectedTheData())
        assertEquals(false, DataAccessException("no SQL cause").rejectedTheData())
    }

    /** The classification must hold for what PostgreSQL really raises, not only for synthetic states. */
    @Test
    fun realPostgreSqlErrorsAreClassifiedAsExpected() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 2
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val club = UUID.randomUUID()
            fun stateOf(block: () -> Unit): String? =
                runCatching(block).exceptionOrNull().let { (it as? DataAccessException)?.sqlState() }
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)", club, "errors-$club")
                // A second club breaks the singleton rule: UNIQUE violation.
                val unique = stateOf { db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Other', ?)",
                    UUID.randomUUID(), "errors-other-${UUID.randomUUID()}") }
                // A blank first name breaks a CHECK constraint (contacts given, so the trigger passes first).
                val check = stateOf { db.execute(
                    """INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone)
                       VALUES (?, '   ', 'Person', DATE '1990-01-01', 'p@example.invalid', '+41000000000')""",
                    UUID.randomUUID()) }
                // An adult without contacts is rejected by a business-rule trigger (plain RAISE EXCEPTION).
                val trigger = stateOf { db.execute(
                    """INSERT INTO members (id, first_name, last_name, date_of_birth)
                       VALUES (?, 'Adult', 'Person', DATE '1990-01-01')""", UUID.randomUUID()) }
                // A statement timeout and a missing table are server-side faults.
                val timeout = stateOf { db.transaction { tx ->
                    DSL.using(tx).execute("SET LOCAL statement_timeout = '50ms'")
                    DSL.using(tx).fetch("SELECT pg_sleep(2)")
                } }
                val missing = stateOf { db.fetch("SELECT 1 FROM table_that_does_not_exist") }
                assertEquals("23505", unique, "unique")
                assertEquals("23514", check, "check")
                assertEquals("P0001", trigger, "trigger")
                assertEquals("57014", timeout, "timeout")
                assertEquals("42P01", missing, "missing table")
                for ((label, state) in mapOf("unique" to unique, "check" to check, "trigger" to trigger)) {
                    assertEquals(true, failure(state).rejectedTheData(), label)
                }
                for ((label, state) in mapOf("timeout" to timeout, "missing table" to missing)) {
                    assertEquals(false, failure(state).rejectedTheData(), label)
                }
            } finally {
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
