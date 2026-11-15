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

class MemberDirectoryTest {
    @Test
    fun filtersPaginatesAndDeniesUnlinkedAccounts() {
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
            val linked = UUID.randomUUID()
            val outsider = UUID.randomUUID()
            val fixtures = listOf(
                Triple(UUID.randomUUID(), "Amy", "Example"),
                Triple(UUID.randomUUID(), "Ben", "Example"),
                Triple(UUID.randomUUID(), "Cora", "Sample"),
                Triple(UUID.randomUUID(), "Dana", "Sample"),
            )
            val duplicateA = UUID.randomUUID()
            val duplicateB = UUID.randomUUID()
            val special = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "directory-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", linked, outsider)
                fixtures.forEachIndexed { index, (id, first, last) ->
                    db.execute(
                        """INSERT INTO members
                           (id, account_id, first_name, last_name, date_of_birth, email, phone)
                           VALUES (?, ?, ?, ?, DATE '1990-01-01', ?, '+41000000000')""",
                        id, if (index == 0) linked else null, first, last,
                        "synthetic-$index@example.invalid",
                    )
                }
                val directory = MemberDirectory(db)
                assertEquals(emptyList<MemberDirectory.Summary>(), directory.search(outsider).members)
                // Even linked accounts have no directory visibility without a grant.
                assertTrue(directory.search(linked).members.isEmpty())
                db.execute("""INSERT INTO club_capability_grants (member_id, capability)
                    VALUES (?, 'VIEW_MEMBER_DIRECTORY')""", fixtures.first().first)
                val first = directory.search(linked, limit = 2)
                assertEquals(listOf("Amy", "Ben"), first.members.map { it.firstName })
                assertNotNull(first.next)
                val second = directory.search(linked, limit = 2, after = first.next)
                assertEquals(listOf("Cora", "Dana"), second.members.map { it.firstName })
                assertNull(second.next)
                assertEquals(listOf("Ben"), directory.search(linked, "bEN exAMple").members.map { it.firstName })
                assertEquals(listOf("Ben"), directory.search(linked, "EXAMPLE BEN").members.map { it.firstName })
                assertTrue(directory.search(linked, "%").members.isEmpty())
                assertThrows(IllegalArgumentException::class.java) {
                    directory.search(linked, query = "a".repeat(101))
                }
                assertThrows(IllegalArgumentException::class.java) { directory.search(linked, limit = 51) }
                assertThrows(IllegalArgumentException::class.java) { directory.search(linked, limit = 0) }
                // Equal names must not lose or repeat a row across UUID-keyed pages.
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, 'Zoe', 'Twin', DATE '1990-01-01', 'twin-a@example.invalid', '+41000000000'),
                           (?, 'Zoe', 'Twin', DATE '1990-01-01', 'twin-b@example.invalid', '+41000000000'),
                           (?, 'Percent_%', 'Literal', DATE '1990-01-01',
                            'literal@example.invalid', '+41000000000')""",
                    duplicateA, duplicateB, special)
                val expected = directory.search(linked, limit = 50).members.map { it.id }
                val seen = mutableListOf<UUID>()
                var cursor: MemberDirectory.After? = null
                for (index in expected.indices) {
                    val page = directory.search(linked, limit = 1, after = cursor)
                    assertEquals(1, page.members.size)
                    seen.add(page.members.single().id)
                    cursor = page.next
                }
                assertNull(cursor)
                assertEquals(expected, seen)
                assertEquals(setOf(duplicateA, duplicateB), seen.toSet().intersect(setOf(duplicateA, duplicateB)))
                // '%' and '_' are literal substrings, never SQL LIKE wildcards.
                assertEquals(listOf(special), directory.search(linked, "%").members.map { it.id })
                assertEquals(listOf(special), directory.search(linked, "_").members.map { it.id })
                assertEquals(listOf(special), directory.search(linked, "percent_%").members.map { it.id })
                // A pagination cursor must never preserve revoked directory privileges.
                val authorizedPage = directory.search(linked, limit = 1)
                assertNotNull(authorizedPage.next)
                db.execute("""DELETE FROM club_capability_grants
                    WHERE member_id = ? AND capability = 'VIEW_MEMBER_DIRECTORY'""",
                    fixtures.first().first)
                val revokedPage = directory.search(linked, limit = 1, after = authorizedPage.next)
                assertTrue(revokedPage.members.isEmpty())
                assertNull(revokedPage.next)
                db.execute("""INSERT INTO club_capability_grants (member_id, capability)
                    VALUES (?, 'VIEW_MEMBER_DIRECTORY')""", fixtures.first().first)
                assertEquals(
                    expected[1],
                    directory.search(linked, limit = 1, after = authorizedPage.next).members.single().id,
                )
                // Unlinking the actor revokes access on the very next query.
                db.execute("UPDATE members SET account_id = NULL WHERE id = ?", fixtures.first().first)
                assertTrue(directory.search(linked).members.isEmpty())
            } finally {
                db.execute("DELETE FROM members WHERE id IN (?, ?, ?)", duplicateA, duplicateB, special)
                fixtures.forEach { (id, _, _) -> db.execute("DELETE FROM members WHERE id = ?", id) }
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", linked, outsider)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
