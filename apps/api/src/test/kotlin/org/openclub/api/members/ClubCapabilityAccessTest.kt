package org.openclub.api.members

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

class ClubCapabilityAccessTest {
    @Test
    fun grantsAreExplicitRevocableAndPermissionManagementAlsoRequiresAdminRole() {
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
            val adminAccount = UUID.randomUUID()
            val memberAccount = UUID.randomUUID()
            val outsider = UUID.randomUUID()
            val admin = UUID.randomUUID()
            val member = UUID.randomUUID()
            val access = ClubCapabilityAccess(db)
            val directory = ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY
            val permissions = ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "capabilities-" + club)
                for (account in listOf(adminAccount, memberAccount, outsider)) {
                    db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                }
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                    VALUES (?, ?, 'Test', 'Admin', DATE '1990-01-01',
                    'admin@example.invalid', '+41000000000', 'ADMIN')""",
                    admin, adminAccount)
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, ?, 'Test', 'Member', DATE '1990-01-01',
                    'member@example.invalid', '+41000000000')""",
                    member, memberAccount)

                assertFalse(access.has(adminAccount, permissions))
                assertFalse(access.has(memberAccount, directory))
                assertFalse(access.has(outsider, directory))
                db.execute("INSERT INTO club_capability_grants VALUES (?, ?)", admin, permissions.name)
                db.execute("INSERT INTO club_capability_grants VALUES (?, ?)", member, directory.name)
                // Grant plus ADMIN role is insufficient without a verified OIDC binding.
                assertFalse(access.has(adminAccount, permissions))
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                    VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                    UUID.randomUUID(), adminAccount, UUID.randomUUID().toString())
                assertTrue(access.has(adminAccount, permissions))
                assertFalse(access.has(memberAccount, directory),
                    "A directory grant without verified identity is insufficient")
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                    VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                    UUID.randomUUID(), memberAccount, "directory-" + memberAccount)
                assertTrue(access.has(memberAccount, directory))
                assertFalse(access.has(memberAccount, permissions))
                assertFalse(access.has(outsider, permissions))

                // Even an explicit permission-manager grant does not override ADMIN role.
                db.execute("INSERT INTO club_capability_grants VALUES (?, ?)", member, permissions.name)
                assertFalse(access.has(memberAccount, permissions))
                db.execute("UPDATE members SET role = 'MEMBER' WHERE id = ?", admin)
                assertFalse(access.has(adminAccount, permissions))
                db.execute("UPDATE members SET role = 'ADMIN' WHERE id = ?", admin)
                assertTrue(access.has(adminAccount, permissions))
                db.execute("DELETE FROM account_identities WHERE account_id = ?", adminAccount)
                assertFalse(access.has(adminAccount, permissions))
                db.execute("DELETE FROM account_identities WHERE account_id = ?", memberAccount)
                assertFalse(access.has(memberAccount, directory))

                assertThrows(DataAccessException::class.java) {
                    db.execute("INSERT INTO club_capability_grants VALUES (?, ?)", member, directory.name)
                }
                assertThrows(DataAccessException::class.java) {
                    db.execute("INSERT INTO club_capability_grants VALUES (?, 'UNREVIEWED_CAPABILITY')", member)
                }
                db.execute("DELETE FROM club_capability_grants WHERE member_id = ? AND capability = ?",
                    member, directory.name)
                assertFalse(access.has(memberAccount, directory))
                db.execute("UPDATE members SET account_id = NULL WHERE id = ?", admin)
                assertFalse(access.has(adminAccount, permissions))
                // A member's club-wide grants must not survive deletion.
                db.execute("INSERT INTO club_capability_grants VALUES (?, ?)", member, directory.name)
                assertFalse(access.has(memberAccount, directory))
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                    VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                    UUID.randomUUID(), memberAccount, "restored-" + memberAccount)
                assertTrue(access.has(memberAccount, directory))
                db.execute("DELETE FROM members WHERE id = ?", member)
                assertEquals(0L, db.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id = ?", member))
                assertFalse(access.has(memberAccount, directory))
            } finally {
                db.execute("DELETE FROM members WHERE id IN (?, ?)", admin, member)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?, ?)",
                    adminAccount, memberAccount, outsider)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
