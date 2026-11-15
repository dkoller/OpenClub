package org.openclub.api.members

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class MemberAccessTest {
    @Test
    fun onlyLinkedLocalAdminMayManageMembers() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val database = DSL.using(source, SQLDialect.POSTGRES)
            val access = MemberAccess(database)
            val admin = UUID.randomUUID()
            val regular = UUID.randomUUID()
            val regularMember = UUID.randomUUID()
            val outsider = UUID.randomUUID()
            val club = UUID.randomUUID()
            try {
                database.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)", club, "Example club", "club-" + club)
                for (account in listOf(admin, regular, outsider)) {
                    database.execute("INSERT INTO accounts (id) VALUES (?)", account)
                }
                database.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                    VALUES (?, ?, 'Example', 'Admin', DATE '1990-01-01', 'admin@example.invalid', '+41000000000', 'ADMIN')""",
                    UUID.randomUUID(), admin)
                database.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth)
                    VALUES (?, ?, 'Example', 'Member', DATE '2015-01-01')""",
                    regularMember, regular)

                assertEquals(club, access.authorizedClubId(admin, MemberAccess.Permission.VIEW_CLUB))
                assertEquals(club, access.authorizedClubId(regular, MemberAccess.Permission.VIEW_CLUB))
                assertNull(access.authorizedClubId(outsider, MemberAccess.Permission.VIEW_CLUB))
                assertEquals(club, access.authorizedClubId(admin, MemberAccess.Permission.MANAGE_MEMBERS))
                assertNull(access.authorizedClubId(regular, MemberAccess.Permission.MANAGE_MEMBERS))
                assertNull(access.authorizedClubId(outsider, MemberAccess.Permission.MANAGE_MEMBERS))

                assertEquals(MemberAccess.Role.ADMIN, access.roleFor(admin))
                assertEquals(MemberAccess.Role.MEMBER, access.roleFor(regular))
                assertNull(access.roleFor(outsider))
                assertTrue(access.canManageMembers(admin))
                assertFalse(access.canManageMembers(regular))
                assertFalse(access.canManageMembers(outsider))

                // Database changes take effect at the next check: no stale role cache.
                database.execute("UPDATE members SET role = 'MEMBER' WHERE account_id = ?", admin)
                assertNull(access.authorizedClubId(admin, MemberAccess.Permission.MANAGE_MEMBERS))
                assertEquals(club, access.authorizedClubId(admin, MemberAccess.Permission.VIEW_CLUB))
                database.execute("UPDATE members SET account_id = NULL WHERE id = ?", regularMember)
                assertNull(access.authorizedClubId(regular, MemberAccess.Permission.VIEW_CLUB))
                database.execute("UPDATE members SET account_id = ? WHERE id = ?", regular, regularMember)
            } finally {
                database.execute("DELETE FROM members WHERE account_id IN (?, ?)", admin, regular)
                database.execute("DELETE FROM accounts WHERE id IN (?, ?, ?)", admin, regular, outsider)
                database.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
