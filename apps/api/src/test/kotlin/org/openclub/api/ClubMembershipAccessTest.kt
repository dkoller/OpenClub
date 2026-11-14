package org.openclub.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ClubMembershipAccessTest {
    @Test
    fun rolesBelongToClubsNotToPeopleGlobally() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 2
        }
        HikariDataSource(config).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val database = DSL.using(source, SQLDialect.POSTGRES)
            val access = ClubMembershipAccess(database)
            val admin = UUID.randomUUID()
            val regular = UUID.randomUUID()
            val outsider = UUID.randomUUID()
            val clubA = UUID.randomUUID()
            val clubB = UUID.randomUUID()
            try {
                database.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)", clubA, "Alpha", "a-${clubA}")
                database.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)", clubB, "Beta", "b-${clubB}")
                for (person in listOf(admin, regular, outsider)) {
                    database.execute(
                        "INSERT INTO persons (id, display_name) VALUES (?, ?)", person, "Synthetic person"
                    )
                }
                database.execute(
                    "INSERT INTO memberships (id, club_id, person_id, role) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), clubA, admin, "ADMIN"
                )
                database.execute(
                    "INSERT INTO memberships (id, club_id, person_id, role) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), clubB, admin, "MEMBER"
                )
                database.execute(
                    "INSERT INTO memberships (id, club_id, person_id, role) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), clubA, regular, "MEMBER"
                )

                assertEquals(ClubMembershipAccess.Role.ADMIN, access.roleFor(admin, clubA))
                assertEquals(ClubMembershipAccess.Role.MEMBER, access.roleFor(admin, clubB))
                assertEquals(ClubMembershipAccess.Role.MEMBER, access.roleFor(regular, clubA))
                assertNull(access.roleFor(regular, clubB))
                assertNull(access.roleFor(outsider, clubA))
                assertNull(access.roleFor(admin, UUID.randomUUID()))
                assertTrue(access.canManageMembers(admin, clubA))
                assertFalse(access.canManageMembers(admin, clubB))
                assertFalse(access.canManageMembers(regular, clubA))
                assertFalse(access.canManageMembers(outsider, clubA))
            } finally {
                database.execute("DELETE FROM memberships WHERE club_id IN (?, ?)", clubA, clubB)
                database.execute("DELETE FROM persons WHERE id IN (?, ?, ?)", admin, regular, outsider)
                database.execute("DELETE FROM clubs WHERE id IN (?, ?)", clubA, clubB)
            }
        }
    }
}
