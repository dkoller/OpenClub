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
    fun onlyLinkedLocalMembersMayReadClubId() {
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

                // Linked membership is insufficient until the provider identity is verified.
                assertNull(access.authorizedClubId(admin))
                assertNull(access.authorizedClubId(regular))
                for (account in listOf(admin, regular)) {
                    database.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), account, "club-access-" + account)
                }
                assertEquals(club, access.authorizedClubId(admin))
                assertEquals(club, access.authorizedClubId(regular))
                assertNull(access.authorizedClubId(outsider))

                // Role labels do not affect the local club-ID read boundary.
                database.execute("UPDATE members SET role = 'MEMBER' WHERE account_id = ?", admin)
                assertEquals(club, access.authorizedClubId(admin))
                database.execute("UPDATE members SET role = 'ADMIN' WHERE account_id = ?", admin)
                assertEquals(club, access.authorizedClubId(admin))
                database.execute("UPDATE members SET account_id = NULL WHERE id = ?", regularMember)
                assertNull(access.authorizedClubId(regular))
                database.execute("UPDATE members SET account_id = ? WHERE id = ?", regular, regularMember)
                assertEquals(club, access.authorizedClubId(regular))
                database.execute("DELETE FROM account_identities WHERE account_id = ?", regular)
                assertNull(access.authorizedClubId(regular),
                    "Revoking the account identity must hide club metadata without unlinking member")
                assertEquals(club, access.authorizedClubId(admin))
            } finally {
                database.execute("DELETE FROM members WHERE account_id IN (?, ?)", admin, regular)
                database.execute("DELETE FROM accounts WHERE id IN (?, ?, ?)", admin, regular, outsider)
                database.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
