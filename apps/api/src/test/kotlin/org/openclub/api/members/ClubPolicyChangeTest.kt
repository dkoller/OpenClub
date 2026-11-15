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

class ClubPolicyChangeTest {
    @Test
    fun stricterPolicyRequiresExistingMembersToBeCompliant() {
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
            val teen = UUID.randomUUID()
            val player = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "policy-" + club)
                val today = requireNotNull(db.fetchOne("SELECT CURRENT_DATE AS today")
                    ?.get("today", LocalDate::class.java))
                db.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth)
                    VALUES (?, 'Teen', 'Synthetic', ?)""", teen, today.minusYears(17))
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth, is_player)
                    VALUES (?, 'Player', 'Synthetic', ?, TRUE)""",
                    player, today.minusYears(10))

                assertThrows(DataAccessException::class.java) {
                    db.execute("UPDATE clubs SET adult_age_years = 16 WHERE id = ?", club)
                }
                assertThrows(DataAccessException::class.java) {
                    db.execute("UPDATE clubs SET gender_required_for_players = TRUE WHERE id = ?", club)
                }
                val original = db.fetchOne("""SELECT adult_age_years, gender_required_for_players
                    FROM clubs WHERE id = ?""", club)
                assertEquals(18, original?.get("adult_age_years", Int::class.java))
                assertEquals(false, original?.get("gender_required_for_players", Boolean::class.java))

                db.execute("""UPDATE members SET email = 'teen@example.invalid',
                    phone = '+41000000000' WHERE id = ?""", teen)
                db.execute("UPDATE members SET gender = 'Self-described' WHERE id = ?", player)
                assertEquals(1, db.execute("""UPDATE clubs SET adult_age_years = 16,
                    gender_required_for_players = TRUE WHERE id = ?""", club))

                assertThrows(DataAccessException::class.java) {
                    db.execute("UPDATE members SET phone = NULL WHERE id = ?", teen)
                }
                assertThrows(DataAccessException::class.java) {
                    db.execute("UPDATE members SET gender = NULL WHERE id = ?", player)
                }
                assertEquals("+41000000000", db.fetchValue(
                    "SELECT phone FROM members WHERE id = ?", teen))
                assertEquals("Self-described", db.fetchValue(
                    "SELECT gender FROM members WHERE id = ?", player))
            } finally {
                db.execute("DELETE FROM members WHERE id IN (?, ?)", teen, player)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
