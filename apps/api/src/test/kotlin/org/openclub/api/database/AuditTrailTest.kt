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
import java.time.Instant
import java.util.UUID

/** The read-only operator command for the audit trail. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditTrailTest {
    private var source: HikariDataSource? = null
    private lateinit var db: DSLContext
    private val member = UUID.randomUUID()
    private val otherMember = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val team = UUID.randomUUID()

    @BeforeAll
    fun open() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        source = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 2
        })
        Flyway.configure().dataSource(source).load().migrate()
        db = DSL.using(source, SQLDialect.POSTGRES)
        // Oldest first: created two days ago, a grant a day ago, a role change now; plus one for another member.
        db.execute("INSERT INTO audit_events (occurred_at, event_type, actor_account_id, target_member_id) " +
            "VALUES (now() - interval '2 days', 'MEMBER_CREATED', ?, ?)", actor, member)
        db.execute("INSERT INTO audit_events (occurred_at, event_type, actor_account_id, target_member_id, capability, scope, team_id) " +
            "VALUES (now() - interval '1 day', 'GRANT_ADDED', ?, ?, 'VIEW_TEAMS', 'TEAM', ?)", actor, member, team)
        db.execute("INSERT INTO audit_events (event_type, actor_account_id, target_member_id, new_role) " +
            "VALUES ('ROLE_CHANGED', ?, ?, 'MEMBER')", actor, member)
        db.execute("INSERT INTO audit_events (event_type, target_member_id) VALUES ('CLUB_BOOTSTRAPPED', ?)", otherMember)
    }

    @AfterAll
    fun close() {
        if (source == null) return
        db.execute("DELETE FROM audit_events WHERE target_member_id IN (?, ?)", member, otherMember)
        source?.close()
        source = null
    }

    @Test
    fun filterDefaultsAndValidationHappenBeforeAnyDatabaseConnection() {
        val defaults = auditFilter(emptyMap())
        assertNull(defaults.since); assertNull(defaults.member); assertEquals(200, defaults.limit)
        assertEquals(Instant.parse("2026-11-18T00:00:00Z"), auditFilter(mapOf("OPENCLUB_AUDIT_SINCE" to "2026-11-18")).since)
        assertEquals(Instant.parse("2026-11-18T10:15:00Z"),
            auditFilter(mapOf("OPENCLUB_AUDIT_SINCE" to "2026-11-18T10:15:00Z")).since)
        assertEquals(member, auditFilter(mapOf("OPENCLUB_AUDIT_MEMBER" to member.toString())).member)
        assertEquals(5000, auditFilter(mapOf("OPENCLUB_AUDIT_LIMIT" to "5000")).limit)
        assertEquals(200, auditFilter(mapOf("OPENCLUB_AUDIT_LIMIT" to " ", "OPENCLUB_AUDIT_SINCE" to "")).limit, "blank means unset")
        for ((key, value) in listOf(
            "OPENCLUB_AUDIT_SINCE" to "yesterday", "OPENCLUB_AUDIT_SINCE" to "2026-13-45",
            "OPENCLUB_AUDIT_MEMBER" to "1234", "OPENCLUB_AUDIT_MEMBER" to member.toString().uppercase().replace("-", ""),
            "OPENCLUB_AUDIT_LIMIT" to "0", "OPENCLUB_AUDIT_LIMIT" to "5001", "OPENCLUB_AUDIT_LIMIT" to "many",
        )) {
            assertThrows(IllegalArgumentException::class.java, { auditFilter(mapOf(key to value)) }, "$key=$value")
        }
    }

    @Test
    fun theTrailIsReadNewestFirstAndCanBeFiltered() {
        val all = AuditTrail.read(db, AuditTrail.Filter(null, member, 10))
        assertEquals(listOf("ROLE_CHANGED", "GRANT_ADDED", "MEMBER_CREATED"), all.map { it.type })
        assertEquals(3, AuditTrail.read(db, AuditTrail.Filter(Instant.now().minusSeconds(3 * 86400), member, 10)).size,
            "since three days ago includes all three")
        assertEquals(2, AuditTrail.read(db, AuditTrail.Filter(Instant.now().minusSeconds(36 * 3600), member, 10)).size,
            "since cuts off the oldest event")
        assertEquals(1, AuditTrail.read(db, AuditTrail.Filter(null, member, 1)).size, "the limit applies")
        assertEquals(listOf("CLUB_BOOTSTRAPPED"), AuditTrail.read(db, AuditTrail.Filter(null, otherMember, 10)).map { it.type })
        assertEquals(emptyList<AuditTrail.Row>(), AuditTrail.read(db, AuditTrail.Filter(null, UUID.randomUUID(), 10)))
    }

    @Test
    fun outputIsTabSeparatedIdentifiersOnlyWithEmptyCellsShownAsDashes() {
        val text = AuditTrail.format(AuditTrail.read(db, AuditTrail.Filter(null, member, 10)))
        val lines = text.trimEnd('\n').split('\n')
        assertEquals("id\toccurred_at_utc\tevent\tactor_account\ttarget_member\tcapability\tscope\tteam\tnew_role\trelated_member", lines[0])
        assertTrue(lines.all { it.split('\t').size == 10 }, "every line has the ten columns")
        val grant = lines.first { it.contains("GRANT_ADDED") }.split('\t')
        assertEquals(listOf("GRANT_ADDED", actor.toString(), member.toString(), "VIEW_TEAMS", "TEAM", team.toString(), "-"),
            grant.subList(2, 9))
        val role = lines.first { it.contains("ROLE_CHANGED") }.split('\t')
        assertEquals(listOf("ROLE_CHANGED", actor.toString(), member.toString(), "-", "-", "-", "MEMBER"), role.subList(2, 9))
    }

    @Test
    fun readingChangesNothing() {
        val before = db.fetchCount(DSL.table("audit_events"))
        AuditTrail.read(db, AuditTrail.Filter(null, null, 5000))
        assertEquals(before, db.fetchCount(DSL.table("audit_events")))
    }
}
