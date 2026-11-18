package org.openclub.api.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.auth.AuthSessions
import java.util.UUID

/** Retention rules approved on 2026-11-18 (ADR-0008): audit events 12 months, orphan accounts 2 years. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionCleanupTest {
    private var source: HikariDataSource? = null
    private lateinit var db: DSLContext
    private val club = UUID.randomUUID()
    private val auditTarget = UUID.randomUUID()
    private val accounts = mutableListOf<UUID>()
    private val members = mutableListOf<UUID>()

    @BeforeAll
    fun open() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        source = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 4
        })
        Flyway.configure().dataSource(source).load().migrate()
        db = DSL.using(source, SQLDialect.POSTGRES)
        db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Retention Club', ?)", club, "retention-$club")
    }

    @AfterAll
    fun close() {
        if (source == null) return
        db.execute("DELETE FROM audit_events WHERE target_member_id = ?", auditTarget)
        db.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", members.toTypedArray())
        db.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.toTypedArray())
        db.execute("DELETE FROM clubs WHERE id = ?", club)
        source?.close()
        source = null
    }

    private fun account(daysSinceLogin: Int, linkedToMember: Boolean = false, identity: Boolean = false): UUID {
        val id = UUID.randomUUID()
        accounts += id
        db.execute("INSERT INTO accounts (id, last_login_at) VALUES (?, now() - make_interval(days => ?))", id, daysSinceLogin)
        if (identity) db.execute(
            """INSERT INTO account_identities (id, account_id, issuer, subject)
               VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), id, "retention-$id")
        if (linkedToMember) {
            val member = UUID.randomUUID()
            members += member
            db.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                   VALUES (?, ?, 'Retention', 'Member', DATE '1990-01-01', 'r@example.invalid', '+41000000000')""", member, id)
        }
        return id
    }

    private fun exists(account: UUID) = db.fetchCount(DSL.table("accounts"), DSL.field("id").eq(account)) == 1
    private fun oldAudit(months: Int) = db.execute(
        """INSERT INTO audit_events (occurred_at, event_type, target_member_id)
           VALUES (now() - make_interval(months => ?, days => 1), 'MEMBER_CREATED', ?)""", months, auditTarget)
    private fun auditRows() = db.fetchCount(DSL.table("audit_events"), DSL.field("target_member_id").eq(auditTarget))

    @Test
    fun auditEventsAreDeletedAfterTwelveMonthsInBoundedBatches() {
        repeat(5) { oldAudit(12) }          // 12 months and a day old: past retention
        db.execute("INSERT INTO audit_events (occurred_at, event_type, target_member_id) " +
            "VALUES (now() - interval '11 months', 'MEMBER_CREATED', ?)", auditTarget)
        db.execute("INSERT INTO audit_events (event_type, target_member_id) VALUES ('MEMBER_CREATED', ?)", auditTarget)
        assertEquals(7, auditRows())

        assertEquals(2, RetentionCleanup.pruneBatch(db, batchSize = 2).auditEvents, "the batch limit holds")
        assertEquals(5, auditRows())
        assertEquals(2, RetentionCleanup.pruneBatch(db, batchSize = 2).auditEvents)
        assertEquals(1, RetentionCleanup.pruneBatch(db, batchSize = 2).auditEvents)
        assertEquals(2, auditRows(), "the 11-month-old and the new event stay")
        assertEquals(0, RetentionCleanup.pruneBatch(db, batchSize = 2).auditEvents)
    }

    @Test
    fun onlyOldAccountsWithoutAMemberAreDeletedWithTheirIdentitiesAndSessions() {
        val old = account(daysSinceLogin = 760, identity = true)   // beyond 2 years in any calendar
        val recent = account(daysSinceLogin = 700, identity = true) // inside 2 years in any calendar
        val linkedOld = account(daysSinceLogin = 1500, linkedToMember = true, identity = true)
        val oldWithSession = account(daysSinceLogin = 0)
        AuthSessions(db).create(oldWithSession)
        db.execute("UPDATE accounts SET last_login_at = now() - interval '800 days' WHERE id = ?", oldWithSession)
        assertEquals(1, db.fetchCount(DSL.table("auth_sessions"), DSL.field("account_id").eq(oldWithSession)))

        RetentionCleanup.pruneBatch(db)

        assertFalse(exists(old), "old orphan account deleted")
        assertFalse(exists(oldWithSession), "its leftover session is deleted with it")
        assertEquals(0, db.fetchCount(DSL.table("auth_sessions"), DSL.field("account_id").eq(oldWithSession)))
        assertEquals(0, db.fetchCount(DSL.table("account_identities"), DSL.field("account_id").eq(old)))
        assertTrue(exists(recent), "an account inside 2 years stays")
        assertTrue(exists(linkedOld), "an account linked to a member is never deleted, however old")
        assertEquals(linkedOld, db.fetchValue("SELECT account_id FROM members WHERE id = ?", members.last()),
            "the member keeps its link")
    }

    @Test
    fun anAccountLockedByAnotherTransactionIsSkippedNotDeleted() {
        val account = account(daysSinceLogin = 800)
        requireNotNull(source).connection.use { other ->
            other.autoCommit = false
            other.createStatement().use { it.execute("SELECT 1 FROM accounts WHERE id = '$account' FOR UPDATE") }
            RetentionCleanup.pruneBatch(db)
            assertTrue(exists(account), "a linking or recovery transaction holding the row wins")
            other.rollback()
        }
        RetentionCleanup.pruneBatch(db)
        assertFalse(exists(account), "once released, the old orphan is deleted")
    }

    @Test
    fun signingInRecordsTheLastLogin() {
        val id = account(daysSinceLogin = 760)
        AuthSessions(db).create(id)
        val ageSeconds = db.fetchValue("SELECT extract(epoch FROM now() - last_login_at) FROM accounts WHERE id = ?", id)
            .toString().toDouble()
        assertTrue(ageSeconds in -5.0..30.0, "last_login_at was reset to now: $ageSeconds")
        db.execute("DELETE FROM auth_sessions WHERE account_id = ?", id)
    }
}
