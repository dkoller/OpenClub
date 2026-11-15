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
import java.time.LocalDate
import java.util.UUID

class MemberCreationTest {
    @Test
    fun accountlessCreationRequiresExplicitGrantAndDatabaseFieldPolicy() {
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
            val actorAccount = UUID.randomUUID()
            val outsiderAccount = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val createdIds = mutableListOf<UUID>()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "create-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", actorAccount, outsiderAccount)
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, ?, 'Test', 'Admin', DATE '1990-01-01',
                            'admin@example.invalid', '+41000000000')""", actor, actorAccount)
                val creation = MemberCreation(db)
                fun adult(name: String) = MemberCreation.Input(
                    name, "Synthetic", LocalDate.of(1990, 1, 1),
                    "member@example.invalid", "+41000000000",
                )
                assertNull(creation.create(actorAccount, adult("Denied")))
                assertNull(creation.create(outsiderAccount, adult("Denied")))
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", actor)
                val first = requireNotNull(creation.create(actorAccount, adult("Alice")))
                createdIds += first
                assertNull(db.fetchValue(
                    "SELECT account_id FROM members WHERE id = ?", first))
                val second = requireNotNull(creation.create(actorAccount, adult("Alice")))
                createdIds += second
                assertNotEquals(first, second)
                val youth = requireNotNull(creation.create(actorAccount,
                    MemberCreation.Input("Youth", "Synthetic", LocalDate.now().minusYears(10))))
                createdIds += youth

                assertThrows(DataAccessException::class.java) {
                    creation.create(actorAccount, MemberCreation.Input(
                        "Adult", "MissingContacts", LocalDate.of(1990, 1, 1)))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    creation.create(actorAccount, adult(" "))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    creation.create(actorAccount, adult("x".repeat(201)))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    creation.create(actorAccount, adult("Future").copy(
                        dateOfBirth = LocalDate.now().plusDays(1)))
                }
                db.execute("UPDATE clubs SET gender_required_for_players = TRUE WHERE id = ?", club)
                assertThrows(DataAccessException::class.java) {
                    creation.create(actorAccount, adult("Player").copy(isPlayer = true))
                }
                val player = requireNotNull(creation.create(actorAccount,
                    adult("Player").copy(isPlayer = true, gender = "Self-described")))
                createdIds += player

                db.execute("DELETE FROM club_capability_grants WHERE member_id = ?", actor)
                assertNull(creation.create(actorAccount, adult("Revoked")))
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", actor)
                db.execute("UPDATE members SET account_id = NULL WHERE id = ?", actor)
                assertNull(creation.create(actorAccount, adult("Unlinked")))
                assertEquals(createdIds.size, db.fetchValue(
                    "SELECT COUNT(*) FROM members WHERE id <> ?", actor)?.toString()?.toInt())
            } finally {
                createdIds.forEach { db.execute("DELETE FROM members WHERE id = ?", it) }
                db.execute("DELETE FROM members WHERE id = ?", actor)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", actorAccount, outsiderAccount)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
