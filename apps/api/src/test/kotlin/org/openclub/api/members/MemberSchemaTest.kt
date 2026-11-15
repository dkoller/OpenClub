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
            val boundaryMinor = UUID.randomUUID()
            val boundaryAdult = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)", club, "Pilot", "pilot-" + club)
                // Direct SQL must not bypass the club bootstrap's raw 200-char limit.
                assertThrows(org.jooq.exception.DataAccessException::class.java) {
                    db.execute("UPDATE clubs SET name = ? WHERE id = ?",
                        "P" + " ".repeat(200), club)
                }
                assertEquals("Pilot", db.fetchValue(
                    "SELECT name FROM clubs WHERE id = ?", club))
                assertEquals(1, db.execute("UPDATE clubs SET name = ? WHERE id = ?",
                    "P".repeat(200), club))
                db.execute("UPDATE clubs SET name = 'Pilot' WHERE id = ?", club)
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
                // Whitespace padding must not bypass raw field size bounds.
                for ((column, maxLength) in listOf(
                    "first_name" to 200, "last_name" to 200, "gender" to 200,
                    "email" to 320, "phone" to 40,
                )) {
                    val prefix = if (column == "email") "abc" else "x"
                    val padded = prefix + " ".repeat(maxLength + 1 - prefix.length)
                    assertThrows(org.jooq.exception.DataAccessException::class.java) {
                        db.execute("UPDATE members SET $column = ? WHERE id = ?", padded, member)
                    }
                }

                assertEquals(1L, db.fetchValue(
                    "SELECT COUNT(*) FROM members WHERE id = ? AND account_id IS NULL", member
                ))

                // An installation-specific adult threshold applies on both INSERT and UPDATE.
                db.execute("UPDATE clubs SET adult_age_years = 16 WHERE id = ?", club)
                val today = requireNotNull(db.fetchOne("SELECT CURRENT_DATE AS today")
                    ?.get("today", LocalDate::class.java))
                val adultBirthDate = today.minusYears(16)
                val minorBirthDate = adultBirthDate.plusDays(1)
                db.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth)
                    VALUES (?, 'Minor', 'Boundary', ?)""", boundaryMinor, minorBirthDate)
                for ((email, phone) in listOf(
                    null to null, "adult@example.invalid" to null, null to "+41000000000"
                )) {
                    assertThrows(org.jooq.exception.DataAccessException::class.java) {
                        db.execute("""INSERT INTO members
                            (id, first_name, last_name, date_of_birth, email, phone)
                            VALUES (?, 'Adult', 'Boundary', ?, ?, ?)""",
                            UUID.randomUUID(), adultBirthDate, email, phone)
                    }
                }
                assertThrows(org.jooq.exception.DataAccessException::class.java) {
                    db.execute("UPDATE members SET date_of_birth = ? WHERE id = ?",
                        adultBirthDate, boundaryMinor)
                }
                assertEquals(minorBirthDate, db.fetchOne(
                    "SELECT date_of_birth FROM members WHERE id = ?", boundaryMinor
                )?.get("date_of_birth", LocalDate::class.java))
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, 'Adult', 'Boundary', ?, 'adult@example.invalid', '+41000000000')""",
                    boundaryAdult, adultBirthDate)
            } finally {
                db.execute("DELETE FROM members WHERE id IN (?, ?, ?)",
                    member, boundaryMinor, boundaryAdult)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
