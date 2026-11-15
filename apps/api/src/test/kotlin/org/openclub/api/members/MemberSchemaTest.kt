package org.openclub.api.members

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class MemberSchemaTest {
    @Test
    fun onlyOneClubAndAccountlessMembersNeedBirthDates() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val club = UUID.randomUUID()
            val member = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)", club, "Pilot", "pilot-" + club)
                assertThrows(org.jooq.exception.DataAccessException::class.java) {
                    db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)",
                        UUID.randomUUID(), "Other", "other-" + UUID.randomUUID())
                }
                assertThrows(org.jooq.exception.DataAccessException::class.java) {
                    db.execute("INSERT INTO members (id, first_name, last_name) VALUES (?, ?, ?)",
                        UUID.randomUUID(), "No", "Birthdate")
                }
                assertThrows(org.jooq.exception.DataAccessException::class.java) {
                    db.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth)
                        VALUES (?, 'Missing', 'Contacts', DATE '1990-01-01')""",
                        UUID.randomUUID())
                }
                // Direct SQL inserts must enforce the same DOB boundaries as service writes.
                for (invalid in listOf(LocalDate.now().plusDays(1), LocalDate.of(1850, 1, 1))) {
                    assertThrows(org.jooq.exception.DataAccessException::class.java) {
                        db.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth)
                            VALUES (?, 'Invalid', 'Birthday', ?)""", UUID.randomUUID(), invalid)
                    }
                }
                val childBirthday = LocalDate.now().minusYears(10)
                db.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth)
                    VALUES (?, 'Accountless', 'Child', ?)""", member, childBirthday)
                assertThrows(org.jooq.exception.DataAccessException::class.java) {
                    db.execute("UPDATE members SET date_of_birth = ? WHERE id = ?",
                        LocalDate.now().plusDays(1), member)
                }
                assertThrows(org.jooq.exception.DataAccessException::class.java) {
                    db.execute("UPDATE members SET date_of_birth = DATE '1850-01-01' WHERE id = ?", member)
                }
                assertEquals(childBirthday, db.fetchOne(
                    "SELECT date_of_birth FROM members WHERE id = ?", member
                )?.get("date_of_birth", LocalDate::class.java))
                // Raw database writes cannot bypass the application gender length/blank rules.
                for (invalid in listOf(" ", "x".repeat(201))) {
                    assertThrows(org.jooq.exception.DataAccessException::class.java) {
                        db.execute("UPDATE members SET gender = ? WHERE id = ?", invalid, member)
                    }
                    assertThrows(org.jooq.exception.DataAccessException::class.java) {
                        db.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth, gender)
                            VALUES (?, 'Synthetic', 'Minor', ?, ?)""",
                            UUID.randomUUID(), childBirthday, invalid)
                    }
                }
                assertNull(db.fetchOne("SELECT gender FROM members WHERE id = ?", member)
                    ?.get("gender", String::class.java))
                db.execute("UPDATE members SET gender = ? WHERE id = ?", "x".repeat(200), member)
                assertEquals("x".repeat(200), db.fetchOne(
                    "SELECT gender FROM members WHERE id = ?", member
                )?.get("gender", String::class.java))
                assertEquals(1L, db.fetchValue(
                    "SELECT COUNT(*) FROM members WHERE id = ? AND account_id IS NULL", member
                ))
            } finally {
                db.execute("DELETE FROM members WHERE id = ?", member)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
