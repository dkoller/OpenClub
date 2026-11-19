package org.openclub.api.members

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.members.ClubCapabilityAccess.Capability
import java.time.LocalDate
import java.util.UUID

/**
 * Audit trail (V12; privacy proposal section 5): every committed change to members, roles and grants
 * writes exactly one identifier-only event in the same transaction; denied, repeated and failed changes
 * write none.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditEventsTest {
    private var source: HikariDataSource? = null
    private lateinit var db: DSLContext
    private val club = UUID.randomUUID()
    private val managerAccount = UUID.randomUUID()
    private val outsiderAccount = UUID.randomUUID()
    private val manager = UUID.randomUUID()
    private val target = UUID.randomUUID()
    private val team = UUID.randomUUID()
    private val created = mutableListOf<UUID>()

    @BeforeAll
    fun fixture() {
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
        try {
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Audit Club', ?)", club, "audit-$club")
            db.execute("INSERT INTO accounts (id) VALUES (?), (?)", managerAccount, outsiderAccount)
            db.execute(
                """INSERT INTO account_identities (id, account_id, issuer, subject)
                   VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), managerAccount, "audit-$managerAccount")
            db.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                   VALUES (?, ?, 'Audit', 'Manager', DATE '1990-01-01', 'm@example.invalid', '+41000000000', 'ADMIN')""",
                manager, managerAccount)
            db.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", manager)
            db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", manager)
            db.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                   VALUES (?, NULL, 'Audit', 'Target', DATE '1992-02-02', 't@example.invalid', '+41000000001', 'ADMIN')""", target)
            db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Audit team $team")
        } catch (failure: Throwable) {
            cleanup()
            throw failure
        }
    }

    @AfterAll
    fun cleanup() {
        if (source == null) return
        val ids = created + manager + target
        db.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[]) OR actor_account_id IN (?, ?)",
            ids.toTypedArray(), managerAccount, outsiderAccount)
        db.execute("DELETE FROM teams WHERE id = ?", team)
        db.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", ids.toTypedArray())
        db.execute("DELETE FROM accounts WHERE id IN (?, ?)", managerAccount, outsiderAccount)
        db.execute("DELETE FROM clubs WHERE id = ?", club)
        source?.close()
        source = null
    }

    private data class Event(val type: String, val actor: UUID?, val target: UUID?, val capability: String?,
                             val scope: String?, val team: UUID?, val role: String?)

    private fun events(member: UUID): List<Event> = db.fetch(
        """SELECT event_type, actor_account_id, target_member_id, capability, scope, team_id, new_role
           FROM audit_events WHERE target_member_id = ? ORDER BY id""", member).map {
        Event(it.get(0, String::class.java), it.get(1, UUID::class.java), it.get(2, UUID::class.java),
            it.get(3, String::class.java), it.get(4, String::class.java), it.get(5, UUID::class.java),
            it.get(6, String::class.java))
    }

    private fun adult(first: String) = MemberCreation.Input(
        first, "Synthetic", LocalDate.of(1990, 1, 1), "x@example.invalid", "+41000000002")

    @Test
    fun memberLifecycleWritesOneEventPerChangeAndNothingForDeniedAttempts() {
        val creation = MemberCreation(db)
        val update = MemberDetailsUpdate(db)
        val archive = MemberArchive(db)
        // Denied: an account with no grant changes nothing and audits nothing.
        assertNull(creation.create(outsiderAccount, adult("Denied")))

        val id = requireNotNull(creation.create(managerAccount, adult("Alice")))
        created += id
        assertTrue(update.updateNames(managerAccount, id, "Alicia", "Synthetic"))
        assertTrue(update.replace(db, managerAccount, id, MemberDetailsUpdate.Input(
            "Alicia", "Synthetic", LocalDate.of(1990, 1, 1), "y@example.invalid", "+41000000003")))
        // Denied and missing-target changes write nothing.
        assertFalse(update.updateNames(outsiderAccount, id, "Nope", "Nope"))
        assertFalse(update.updateNames(managerAccount, UUID.randomUUID(), "Nope", "Nope"))
        assertFalse(archive.archive(outsiderAccount, id))
        assertTrue(archive.archive(managerAccount, id))
        assertFalse(archive.archive(managerAccount, id), "a repeated archive changes nothing")
        assertFalse(update.updateNames(managerAccount, id, "After", "Archive"), "archived members cannot be edited")

        assertEquals(
            listOf("MEMBER_CREATED", "MEMBER_UPDATED", "MEMBER_UPDATED", "MEMBER_ARCHIVED"),
            events(id).map { it.type })
        assertTrue(events(id).all { it.actor == managerAccount && it.target == id && it.capability == null &&
            it.scope == null && it.team == null && it.role == null })
    }

    @Test
    fun roleAndGrantChangesAreAuditedOnlyWhenTheyChangeSomething() {
        val roles = MemberRoles(db)
        val grants = CapabilityGrants(db)
        assertEquals(MemberRoles.Change.DENIED, roles.change(outsiderAccount, target, MemberRoles.Role.MEMBER))
        assertEquals(MemberRoles.Change.UPDATED, roles.change(managerAccount, target, MemberRoles.Role.MEMBER))
        assertEquals(MemberRoles.Change.UNCHANGED, roles.change(managerAccount, target, MemberRoles.Role.MEMBER))

        val directory = Capability.VIEW_MEMBER_DIRECTORY
        assertEquals(CapabilityGrants.Change.DENIED, grants.grant(outsiderAccount, target, directory, CapabilityGrants.Scope.CLUB))
        assertEquals(CapabilityGrants.Change.GRANTED, grants.grant(managerAccount, target, directory, CapabilityGrants.Scope.CLUB))
        assertEquals(CapabilityGrants.Change.UNCHANGED, grants.grant(managerAccount, target, directory, CapabilityGrants.Scope.CLUB))
        assertEquals(CapabilityGrants.Change.GRANTED,
            grants.grant(managerAccount, target, Capability.VIEW_TEAMS, CapabilityGrants.Scope.TEAM, team))
        assertEquals(CapabilityGrants.Change.REVOKED, grants.revoke(managerAccount, target, directory, CapabilityGrants.Scope.CLUB))
        assertEquals(CapabilityGrants.Change.UNCHANGED, grants.revoke(managerAccount, target, directory, CapabilityGrants.Scope.CLUB))

        assertEquals(
            listOf(
                Event("ROLE_CHANGED", managerAccount, target, null, null, null, "MEMBER"),
                Event("GRANT_ADDED", managerAccount, target, "VIEW_MEMBER_DIRECTORY", "CLUB", null, null),
                Event("GRANT_ADDED", managerAccount, target, "VIEW_TEAMS", "TEAM", team, null),
                Event("GRANT_REVOKED", managerAccount, target, "VIEW_MEMBER_DIRECTORY", "CLUB", null, null),
            ),
            events(target))
    }

    @Test
    fun aFailedChangeLeavesNoEvent() {
        val before = db.fetchCount(DSL.table("audit_events"))
        // An adult without contacts is rejected by the database trigger inside the same statement.
        val failure = runCatching {
            MemberCreation(db).create(managerAccount, MemberCreation.Input("No", "Contacts", LocalDate.of(1990, 1, 1)))
        }.exceptionOrNull()
        assertTrue(failure is DataAccessException, "the insert fails: $failure")
        assertEquals(before, db.fetchCount(DSL.table("audit_events")))
    }

    @Test
    fun theTrailHoldsIdentifiersOnlyAndCannotBeRewritten() {
        val columns = db.fetch(
            """SELECT column_name FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'audit_events'""")
            .map { it.get(0, String::class.java) }.toSet()
        assertEquals(setOf("id", "occurred_at", "event_type", "actor_account_id", "target_member_id", "capability",
            "scope", "team_id", "new_role", "related_member_id"), columns, "no name, birth date, contact or free-text column")

        val id = requireNotNull(MemberCreation(db).create(managerAccount, adult("Immutable")))
        created += id
        fun state(block: () -> Unit) = (runCatching(block).exceptionOrNull() as? DataAccessException)?.sqlState()
        assertEquals("P0001", state { db.execute("UPDATE audit_events SET event_type = 'MEMBER_ARCHIVED' WHERE target_member_id = ?", id) })
        // Shape rules: a role event needs its role, a grant event its capability and scope.
        assertEquals("23514", state { db.execute(
            "INSERT INTO audit_events (event_type, target_member_id) VALUES ('ROLE_CHANGED', ?)", id) })
        assertEquals("23514", state { db.execute(
            "INSERT INTO audit_events (event_type, target_member_id) VALUES ('GRANT_ADDED', ?)", id) })
        assertEquals("23514", state { db.execute(
            "INSERT INTO audit_events (event_type, target_member_id) VALUES ('MEMBER_CREATED', NULL)") })
        // The retention job may delete rows.
        assertEquals(1, db.execute("DELETE FROM audit_events WHERE target_member_id = ?", id))
    }
}
