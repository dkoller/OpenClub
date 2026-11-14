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

class MembershipSchemaTest {
    @Test
    fun membershipRolesAreScopedToTheirClub() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
        }

        HikariDataSource(config).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val clubA = UUID.randomUUID()
            val clubB = UUID.randomUUID()
            val person = UUID.randomUUID()
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)", clubA, "Club A", "a-" + clubA)
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)", clubB, "Club B", "b-" + clubB)
                db.execute("INSERT INTO persons (id, display_name) VALUES (?, ?)", person, "Sample Person")
                db.execute("INSERT INTO memberships (id, club_id, person_id, role) VALUES (?, ?, ?, ?)", a, clubA, person, "ADMIN")
                db.execute("INSERT INTO memberships (id, club_id, person_id, role) VALUES (?, ?, ?, ?)", b, clubB, person, "MEMBER")

                assertEquals("ADMIN", db.fetchValue(
                    "SELECT role FROM memberships WHERE club_id = ? AND person_id = ?", clubA, person
                ))
                assertEquals("MEMBER", db.fetchValue(
                    "SELECT role FROM memberships WHERE club_id = ? AND person_id = ?", clubB, person
                ))
                assertEquals(1, db.fetchCount(
                    DSL.table(DSL.name("memberships")),
                    DSL.field(DSL.name("club_id"), UUID::class.java).eq(clubA)
                ))
                // The database prevents duplicate membership within a club.
                assertThrows(org.jooq.exception.DataAccessException::class.java) {
                    db.execute(
                        "INSERT INTO memberships (id, club_id, person_id, role) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), clubA, person, "MEMBER"
                    )
                }
            } finally {
                db.execute("DELETE FROM memberships WHERE club_id IN (?, ?)", clubA, clubB)
                db.execute("DELETE FROM persons WHERE id = ?", person)
                db.execute("DELETE FROM clubs WHERE id IN (?, ?)", clubA, clubB)
            }
        }
    }
}
