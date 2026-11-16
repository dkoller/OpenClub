package org.openclub.api.teams

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openclub.api.members.CapabilityGrants
import org.openclub.api.members.ClubCapabilityAccess
import java.util.UUID

class ArchivedActorAuthorizationTest {
    @Test
    fun archivedLinkedActorCannotRecoverPermissionsOrManageTeam() {
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
            val account = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val recipient = UUID.randomUUID()
            val team = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "archived-actor-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                    UUID.randomUUID(), account, UUID.randomUUID().toString())
                db.execute("""INSERT INTO members (id, account_id, first_name, last_name,
                    date_of_birth, email, phone, role)
                    VALUES (?, ?, 'Actor', 'Synthetic', DATE '1990-01-01',
                        'actor@example.invalid', '+41000000000', 'ADMIN')""", actor, account)
                db.execute("""INSERT INTO members (id, first_name, last_name,
                    date_of_birth, email, phone)
                    VALUES (?, 'Recipient', 'Synthetic', DATE '1990-01-01',
                        'recipient@example.invalid', '+41000000000')""", recipient)
                db.execute("INSERT INTO teams (id, name) VALUES (?, 'Team')", team)
                db.execute("""INSERT INTO club_capability_grants (member_id, capability) VALUES
                    (?, 'VIEW_TEAMS'), (?, 'MANAGE_TEAMS'), (?, 'MANAGE_PERMISSIONS')""",
                    actor, actor, actor)
                val access = ClubCapabilityAccess(db)
                val ops = TeamAssignments(db)
                val manage = ClubCapabilityAccess.Capability.MANAGE_TEAMS
                val permissions = ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS
                assertTrue(access.has(account, manage))
                assertTrue(access.hasForTeam(account, team, manage))
                assertEquals(1, ops.list(account).size)
                assertTrue(ops.assign(account, team, recipient))
                // Database constraints also forbid archived ADMINs and linked accounts;
                // the archival trigger independently rejects retained grants.
                assertThrows(DataAccessException::class.java) {
                    db.execute("UPDATE members SET archived_at = NOW() WHERE id = ?", actor)
                }
                db.execute("DELETE FROM club_capability_grants WHERE member_id = ?", actor)
                db.execute("""UPDATE members SET account_id = NULL, role = 'MEMBER',
                              archived_at = NOW() WHERE id = ?""", actor)
                assertFalse(access.has(account, manage))
                assertFalse(access.has(account, permissions))
                assertFalse(access.hasForTeam(account, team, manage))
                assertTrue(ops.list(account).isEmpty())
                assertNull(ops.create(account, "Denied"))
                assertFalse(ops.rename(account, team, "Denied"))
                assertFalse(ops.remove(account, team))
                assertFalse(ops.assign(account, team, recipient))
                assertFalse(ops.unassign(account, team, recipient))
                assertEquals(CapabilityGrants.Change.DENIED,
                    CapabilityGrants(db).grant(account, recipient,
                        ClubCapabilityAccess.Capability.VIEW_TEAMS,
                        CapabilityGrants.Scope.CLUB))
                assertThrows(DataAccessException::class.java) {
                    db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_TEAMS')", actor)
                }
            } finally {
                db.execute("DELETE FROM teams WHERE id = ?", team)
                db.execute("DELETE FROM members WHERE id IN (?, ?)", actor, recipient)
                db.execute("DELETE FROM accounts WHERE id = ?", account)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
