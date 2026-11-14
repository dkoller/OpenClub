package org.openclub.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthRecordCleanupTest {
    @Test
    fun rejectsUnboundedBatchSizes() {
        // Argument validation happens before any database access.
        assertThrows(IllegalArgumentException::class.java) {
            AuthRecordCleanup.pruneBatch(DSL.using(SQLDialect.POSTGRES), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthRecordCleanup.pruneBatch(DSL.using(SQLDialect.POSTGRES), 1001)
        }
    }

    @Test
    fun deletesOnlyExpiredRecordsInLimitedBatches() {
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
            val prefix = UUID.randomUUID().toString().replace("-", "")
            val hashes = (1..3).map { i -> (prefix + i.toString().padStart(32, '0')) }
            val person = UUID.randomUUID()
            db.execute("INSERT INTO persons (id, display_name) VALUES (?, ?)", person, "Cleanup test person")
            try {
                for (i in 0..2) {
                    db.execute(
                        """INSERT INTO oidc_login_transactions
                            (state_hash, browser_hash, nonce, code_verifier, expires_at)
                            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 minute'))""",
                        hashes[i], hashes[i], "n".repeat(43), "v".repeat(43), if (i == 2) 5 else -5,
                    )
                    db.execute(
                        """INSERT INTO auth_sessions
                            (session_hash, person_id, csrf_token, idle_expires_at, absolute_expires_at)
                            VALUES (?, ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 minute'),
                                CURRENT_TIMESTAMP + INTERVAL '12 hours')""",
                        hashes[i], person, "c".repeat(43), if (i == 2) 5 else -5,
                    )
                }
                assertEquals(AuthRecordCleanup.Counts(1, 1), AuthRecordCleanup.pruneBatch(db, 1))
                assertEquals(AuthRecordCleanup.Counts(1, 1), AuthRecordCleanup.pruneBatch(db, 1))
                assertEquals(AuthRecordCleanup.Counts(0, 0), AuthRecordCleanup.pruneBatch(db, 1))
                assertEquals(1L, db.fetchValue(
                    "SELECT COUNT(*) FROM oidc_login_transactions WHERE state_hash = ?", hashes[2],
                ))
                assertEquals(1L, db.fetchValue(
                    "SELECT COUNT(*) FROM auth_sessions WHERE session_hash = ?", hashes[2],
                ))
            } finally {
                db.execute("DELETE FROM oidc_login_transactions WHERE state_hash IN (?, ?, ?)", *hashes.toTypedArray())
                db.execute("DELETE FROM persons WHERE id = ?", person)
            }
        }
    }
}
