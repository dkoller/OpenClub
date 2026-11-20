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
    fun archivedMembersAreErasedTwentyFourMonthsAfterArchivingWithAnAuditEvent() {
        fun archived(monthsAgo: Int): UUID {
            val id = UUID.randomUUID()
            members += id
            db.execute(
                """INSERT INTO members (id, first_name, last_name, date_of_birth, archived_at)
                   VALUES (?, 'Retention', 'Archived', DATE '2012-01-01', now() - make_interval(months => ?, days => 1))""",
                id, monthsAgo)
            return id
        }
        val old = List(3) { archived(24) }       // 24 months and a day: past retention
        val recent = archived(23)
        val active = UUID.randomUUID().also {
            members += it
            db.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Retention', 'Active', DATE '2012-01-01')", it)
        }
        fun present(id: UUID) = db.fetchCount(DSL.table("members"), DSL.field("id").eq(id)) == 1

        assertEquals(2, RetentionCleanup.pruneBatch(db, batchSize = 2).erasedMembers, "the batch limit holds")
        assertEquals(1, RetentionCleanup.pruneBatch(db, batchSize = 2).erasedMembers)
        assertEquals(0, RetentionCleanup.pruneBatch(db, batchSize = 2).erasedMembers)
        assertTrue(old.none(::present), "every member archived over 24 months ago is erased")
        assertTrue(present(recent), "an archive inside 24 months stays")
        assertTrue(present(active), "an active member is never touched")
        for (id in old) {
            val events = db.fetch("SELECT actor_account_id FROM audit_events WHERE event_type = 'MEMBER_ERASED' AND target_member_id = ?", id)
            assertEquals(1, events.size, "one event per erased member")
            assertNull(events[0].get(0), "the retention job is not a browser-session actor")
        }
        assertEquals(0, db.fetchCount(DSL.table("audit_events"),
            DSL.field("event_type").eq("MEMBER_ERASED").and(DSL.field("target_member_id").`in`(listOf(recent, active)))))
        db.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", old.toTypedArray())
    }

    @Test
    fun clubRecordsExpireAsTheControllerDecided() {
        // Controller decision C10 (2026-11-20): attendance 12 months after the event, events 24 months, fee periods
        // 24 months after the due date, announcements 6 months after show-until. Each just past and just inside.
        val team = UUID.randomUUID()
        val member = UUID.randomUUID().also { members += it }
        db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Retention team ${team.toString().take(8)}")
        db.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Retention', 'Records', DATE '2012-01-01')", member)
        db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, member)
        fun event(monthsAgo: Int): UUID = UUID.randomUUID().also {
            db.execute(
                """INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at)
                   VALUES (?, ?, 'TRAINING', 'Retention', now() - make_interval(months => ?, days => 1, hours => 2),
                           now() - make_interval(months => ?, days => 1))""", it, team, monthsAgo, monthsAgo)
            db.execute("INSERT INTO event_attendance (event_id, member_id, response) VALUES (?, ?, 'YES')", it, member)
            // A volunteer shift over the same hours, with the member signed up (since OpenAPI 1.16.0).
            val shift = UUID.randomUUID()
            db.execute("""INSERT INTO event_shifts (id, event_id, title, starts_at, ends_at, needed)
                          SELECT ?, id, 'Bar', starts_at, ends_at, 1 FROM team_events WHERE id = ?""", shift, it)
            db.execute("INSERT INTO shift_volunteers (shift_id, member_id) VALUES (?, ?)", shift, member)
        }
        fun period(monthsAgo: Int): UUID = UUID.randomUUID().also {
            db.execute("""INSERT INTO fee_periods (id, name, due_date, amount_cents)
                          VALUES (?, ?, (CURRENT_DATE - make_interval(months => ?, days => 1))::date, 100)""", it, "Retention $it", monthsAgo)
            db.execute("INSERT INTO member_fees (period_id, member_id, amount_cents) VALUES (?, ?, 100)", it, member)
        }
        fun announcement(monthsAgo: Int): UUID = UUID.randomUUID().also {
            db.execute("""INSERT INTO announcements (id, title, body, show_until)
                          VALUES (?, 'Retention', 'x', (CURRENT_DATE - make_interval(months => ?, days => 1))::date)""", it, monthsAgo)
        }
        val expiredEvent = event(24); val keptEvent = event(23)
        val answerOnly = event(12); val keptAnswer = event(11)
        val expiredPeriod = period(24); val keptPeriod = period(23)
        val expiredNews = announcement(6); val keptNews = announcement(5)
        fun count(table: String, column: String, id: UUID) = db.fetchCount(DSL.table(table), DSL.field(column).eq(id))

        try {
            repeat(3) { RetentionCleanup.pruneBatch(db) }
            assertEquals(0, count("team_events", "id", expiredEvent), "an event over 24 months past is deleted")
            assertEquals(1, count("team_events", "id", keptEvent))
            assertEquals(1, count("team_events", "id", answerOnly), "the event itself stays for 24 months")
            assertEquals(0, count("event_attendance", "event_id", answerOnly), "its answers go after 12 months")
            assertEquals(1, count("event_attendance", "event_id", keptAnswer))
            fun volunteers(event: UUID) = db.fetchCount(DSL.table("shift_volunteers v JOIN event_shifts s ON s.id = v.shift_id"),
                DSL.field("s.event_id").eq(event))
            assertEquals(0, volunteers(answerOnly), "sign-ups go 12 months after the shift")
            assertEquals(1, volunteers(keptAnswer))
            assertEquals(0, count("fee_periods", "id", expiredPeriod))
            assertEquals(0, count("member_fees", "period_id", expiredPeriod), "the fees go with the period")
            assertEquals(1, count("member_fees", "period_id", keptPeriod))
            assertEquals(0, count("announcements", "id", expiredNews))
            assertEquals(1, count("announcements", "id", keptNews))
            assertEquals(1, db.fetchCount(DSL.table("members"), DSL.field("id").eq(member)), "the member is untouched")
        } finally {
            db.execute("DELETE FROM teams WHERE id = ?", team)
            db.execute("DELETE FROM fee_periods WHERE id IN (?, ?)", expiredPeriod, keptPeriod)
            db.execute("DELETE FROM announcements WHERE id IN (?, ?)", expiredNews, keptNews)
        }
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
