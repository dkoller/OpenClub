package org.openclub.api.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.exception.FlywayValidateException
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openclub.api.members.ClubCapabilityAccess
import java.util.UUID

/**
 * Upgrade path (#27): an installation at an older schema version, holding data, must migrate to the
 * current schema without losing or breaking that data. Each test works in its own throwaway schema,
 * so it never touches the tables the other suites use. Flyway has no undo; the supported rollback is
 * to restore the backup taken before the upgrade (docs/deployment/upgrade-and-rollback.md).
 */
class MigrationUpgradeTest {
    private val adminAccount = UUID.randomUUID()
    private val admin = UUID.randomUUID()
    private val adult = UUID.randomUUID()
    private val minor = UUID.randomUUID()
    private val teamA = UUID.randomUUID()
    private val teamB = UUID.randomUUID()

    private fun source(schema: String?): HikariDataSource {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        return HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 2
            if (schema != null) this.schema = schema
        })
    }

    private fun flyway(source: HikariDataSource, schema: String, target: String? = null): Flyway =
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
            .apply { if (target != null) target(target) }.load()

    /** Runs [block] against a fresh schema and always drops it afterwards. */
    private fun inScratchSchema(block: (schema: String, source: HikariDataSource, db: DSLContext) -> Unit) {
        val schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "").take(12)
        source(null).use { admin ->
            val setup = DSL.using(admin, SQLDialect.POSTGRES)
            setup.execute("CREATE SCHEMA $schema")
            try {
                source(schema).use { scoped ->
                    block(schema, scoped, DSL.using(scoped, SQLDialect.POSTGRES))
                }
            } finally {
                setup.execute("DROP SCHEMA $schema CASCADE")
            }
        }
    }

    /** Synthetic data that is valid at schema version 5: members, grants, teams, assignments. */
    private fun seedVersion5(db: DSLContext) {
        db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Upgrade Club', 'upgrade-club')", UUID.randomUUID())
        db.execute("INSERT INTO accounts (id) VALUES (?)", adminAccount)
        db.execute(
            """INSERT INTO account_identities (id, account_id, issuer, subject)
               VALUES (?, ?, 'https://issuer.example.invalid', 'upgrade-subject')""", UUID.randomUUID(), adminAccount)
        db.execute(
            """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
               VALUES (?, ?, 'Synthetic', 'Administrator', DATE '1990-01-01', 'a@example.invalid', '+41000000000', 'ADMIN')""",
            admin, adminAccount)
        db.execute(
            """INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone, is_player, gender)
               VALUES (?, 'Synthetic', 'Adult', DATE '1992-02-02', 'b@example.invalid', '+41000000001', TRUE, 'f')""", adult)
        db.execute(
            """INSERT INTO members (id, first_name, last_name, date_of_birth)
               VALUES (?, 'Synthetic', 'Minor', DATE '2016-03-03')""", minor)
        db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", admin)
        db.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", admin)
        db.execute("INSERT INTO teams (id, name) VALUES (?, 'Juniors')", teamA)
        db.execute("INSERT INTO teams (id, name) VALUES (?, 'Seniors')", teamB)
        db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamA, minor)
        db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'VIEW_TEAMS')", teamA, adult)
    }

    private fun count(db: DSLContext, table: String): Long =
        db.fetchValue("SELECT COUNT(*) FROM $table")?.toString()?.toLong() ?: -1

    private fun state(block: () -> Unit): String? =
        runCatching(block).exceptionOrNull().let { (it as? DataAccessException)?.sqlState() }

    @Test
    fun anInstallationAtSchemaVersion5WithDataUpgradesToTheCurrentSchemaIntact() = inScratchSchema { schema, source, db ->
        flyway(source, schema, target = "5").migrate()
        assertEquals("5", flyway(source, schema).info().current().version.version)
        seedVersion5(db)

        val result = flyway(source, schema).migrate()
        assertTrue(result.migrationsExecuted >= 6, "migrations V6 and later ran: ${result.migrationsExecuted}")
        val current = flyway(source, schema).info()
        assertEquals(current.all().last().version, current.current().version, "at the latest version")
        assertEquals(0, current.pending().size, "nothing pending")
        flyway(source, schema).validate() // recorded checksums still match the shipped migrations

        // Every row survived, unchanged.
        assertEquals(listOf(1L, 1L, 1L, 3L, 2L, 2L, 1L, 1L),
            listOf("clubs", "accounts", "account_identities", "members", "club_capability_grants", "teams",
                "member_team_assignments", "team_capability_grants").map { count(db, it) })
        assertEquals("ADMIN", db.fetchValue("SELECT role FROM members WHERE id = ?", admin))
        assertEquals(adminAccount, db.fetchValue("SELECT account_id FROM members WHERE id = ?", admin))
        assertEquals(0L, count(db, "members WHERE archived_at IS NOT NULL"), "new column starts empty")
        assertEquals(setOf("Juniors", "Seniors"), db.fetch("SELECT name FROM teams").map { it.get(0) as String }.toSet())

        // The application services work on the upgraded data.
        val access = ClubCapabilityAccess(db)
        assertTrue(access.has(adminAccount, ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS))
        assertTrue(access.has(adminAccount, ClubCapabilityAccess.Capability.EDIT_MEMBERS))
        assertFalse(access.has(adminAccount, ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY))

        // Constraints added by later migrations now apply to the old data's tables.
        assertEquals("23505", state { db.execute("INSERT INTO teams (id, name) VALUES (?, '  JUNIORS ')", UUID.randomUUID()) },
            "V6: team names are unique ignoring case and padding")
        // V10 adds a CHECK and V11 a trigger for the same rule; the trigger fires first (P0001).
        assertTrue(state { db.execute("UPDATE members SET archived_at = now() WHERE id = ?", admin) } in setOf("23514", "P0001"),
            "V10/V11: a linked administrator cannot be archived")
    }

    @Test
    fun anInstallationAtSchemaVersion1UpgradesToTheCurrentSchema() = inScratchSchema { schema, source, db ->
        flyway(source, schema, target = "1").migrate()
        db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'First Release Club', 'first-release')", UUID.randomUUID())
        db.execute("INSERT INTO accounts (id) VALUES (?)", adminAccount)
        db.execute(
            """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
               VALUES (?, ?, 'Synthetic', 'Administrator', DATE '1990-01-01', 'a@example.invalid', '+41000000000', 'ADMIN')""",
            admin, adminAccount)

        flyway(source, schema).migrate()
        val info = flyway(source, schema).info()
        assertEquals(info.all().last().version, info.current().version)
        assertEquals(listOf(1L, 1L, 1L), listOf("clubs", "accounts", "members").map { count(db, it) })
        assertEquals("First Release Club", db.fetchValue("SELECT name FROM clubs"))
    }

    /**
     * Data that older schema versions allowed can block a later migration. The preflight queries in the
     * upgrade runbook must find it, a blocked migration must leave the schema at its old version
     * (PostgreSQL rolls the failed migration back), and fixing the data must let the upgrade finish.
     */
    @Test
    fun preflightQueriesFindDataThatBlocksTheUpgradeAndFixingItLetsTheUpgradeFinish() = inScratchSchema { schema, source, db ->
        flyway(source, schema, target = "5").migrate()
        seedVersion5(db)
        // V6 rejects team names equal after trimming; V8 rejects raw lengths over 200 (padding is allowed before).
        db.execute("INSERT INTO teams (id, name) VALUES (?, 'juniors ')", UUID.randomUUID())
        db.execute("UPDATE members SET last_name = 'Padded' || repeat(' ', 250) WHERE id = ?", adult)

        val duplicateTeams = db.fetch(PREFLIGHT_DUPLICATE_TEAMS).size
        val overlong = db.fetch(PREFLIGHT_OVERLONG_VALUES).size
        assertEquals(1, duplicateTeams, "preflight finds the duplicate team names")
        assertEquals(1, overlong, "preflight finds the overlong member value")
        assertEquals(0, db.fetch(PREFLIGHT_OVERLONG_NAMES).size, "no overlong team or club name")

        assertThrows(Exception::class.java) { flyway(source, schema).migrate() }
        assertEquals("5", flyway(source, schema).info().current().version.version,
            "a blocked migration leaves the schema where it was")
        assertEquals(3L, count(db, "teams"), "and loses no data")

        db.execute("DELETE FROM teams WHERE name = 'juniors '")
        db.execute("UPDATE members SET last_name = 'Padded' WHERE id = ?", adult)
        assertEquals(0, db.fetch(PREFLIGHT_DUPLICATE_TEAMS).size)
        assertEquals(0, db.fetch(PREFLIGHT_OVERLONG_VALUES).size)

        flyway(source, schema).migrate()
        val info = flyway(source, schema).info()
        assertEquals(info.all().last().version, info.current().version)
        assertEquals("Padded", db.fetchValue("SELECT last_name FROM members WHERE id = ?", adult))
    }

    @Test
    fun aMigrationWhoseChecksumChangedAfterItWasAppliedIsRefused() = inScratchSchema { schema, source, db ->
        flyway(source, schema).migrate()
        db.execute("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '2'")
        assertThrows(FlywayValidateException::class.java) { flyway(source, schema).validate() }
    }

    private companion object {
        /** Same text as the preflight queries in docs/deployment/upgrade-and-rollback.md. */
        const val PREFLIGHT_DUPLICATE_TEAMS =
            "SELECT lower(trim(name)) FROM teams GROUP BY 1 HAVING COUNT(*) > 1"
        const val PREFLIGHT_OVERLONG_VALUES =
            """SELECT id FROM members WHERE length(first_name) > 200 OR length(last_name) > 200
               OR length(email) > 320 OR length(phone) > 40 OR length(gender) > 200"""
        const val PREFLIGHT_OVERLONG_NAMES =
            "SELECT id FROM teams WHERE length(name) > 200 UNION ALL SELECT id FROM clubs WHERE length(name) > 200"
    }
}
