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

class MemberDetailsUpdateTest {
    @Test
    fun updatesOnlyPrivateMemberDetailsAndNeverRoleAccountOrGrants() {
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
            val account = UUID.randomUUID()
            val outsider = UUID.randomUUID()
            val editor = UUID.randomUUID()
            val target = UUID.randomUUID()
            val youth = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "update-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", account, outsider)
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, ?, 'Editor', 'Synthetic', DATE '1990-01-01',
                            'editor@example.invalid', '+41000000000')""", editor, account)
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth, email, phone, role)
                    VALUES (?, 'Original', 'Synthetic', DATE '1990-01-01',
                            'target@example.invalid', '+41000000000', 'ADMIN')""", target)
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_TEAMS')", target)
                val service = MemberDetailsUpdate(db)
                val update = MemberDetailsUpdate.Input(
                    "Changed", "Surname", LocalDate.of(1991, 5, 1),
                    "changed@example.invalid", "+41000000001", "Other",
                    true,
                )
                assertFalse(service.update(account, target, update))
                assertFalse(service.update(outsider, target, update))
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", editor)
                assertTrue(service.update(account, target, update))
                val row = db.fetchOne(
                    """SELECT account_id, first_name, last_name, role, email, phone, gender,
                              is_player, date_of_birth FROM members WHERE id = ?""", target)
                assertNotNull(row)
                assertEquals("Changed", row?.get("first_name", String::class.java))
                assertEquals("Surname", row?.get("last_name", String::class.java))
                assertEquals("ADMIN", row?.get("role", String::class.java))
                assertNull(row?.get("account_id", UUID::class.java))
                assertEquals("changed@example.invalid", row?.get("email", String::class.java))
                assertEquals("+41000000001", row?.get("phone", String::class.java))
                assertEquals("Other", row?.get("gender", String::class.java))
                assertEquals(true, row?.get("is_player", Boolean::class.java))
                assertEquals(1, db.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id = ?", target
                )?.toString()?.toInt())
                assertFalse(service.update(account, UUID.randomUUID(), update))
                assertThrows(IllegalArgumentException::class.java) {
                    service.update(account, target, update.copy(firstName = " "))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    service.update(account, target, update.copy(dateOfBirth = LocalDate.now().plusDays(1)))
                }
                // A rejected policy update must not partially persist any changed fields.
                val beforeRejectedUpdate = db.fetchOne(
                    """SELECT first_name, last_name, date_of_birth, email, phone, gender,
                              is_player, role, account_id FROM members WHERE id = ?""", target
                )?.intoMap()
                assertNotNull(beforeRejectedUpdate)
                assertThrows(DataAccessException::class.java) {
                    service.update(account, target, update.copy(
                        firstName = "MustNotPersist", email = null))
                }
                assertEquals(beforeRejectedUpdate, db.fetchOne(
                    """SELECT first_name, last_name, date_of_birth, email, phone, gender,
                              is_player, role, account_id FROM members WHERE id = ?""", target
                )?.intoMap())
                db.execute("UPDATE clubs SET gender_required_for_players = TRUE WHERE id = ?", club)
                assertThrows(DataAccessException::class.java) {
                    service.update(account, target, update.copy(gender = null))
                }
                // An age transition cannot evade adult-contact validation on UPDATE.
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth)
                    VALUES (?, 'Young', 'Synthetic', ?)""",
                    youth, LocalDate.now().minusYears(10))
                assertThrows(DataAccessException::class.java) {
                    service.update(account, youth, MemberDetailsUpdate.Input(
                        "Adult", "Synthetic", LocalDate.of(1990, 1, 1)))
                }
                val unchanged = db.fetchOne(
                    "SELECT first_name, email, phone, date_of_birth FROM members WHERE id = ?", youth)
                assertEquals("Young", unchanged?.get("first_name", String::class.java))
                assertNull(unchanged?.get("email", String::class.java))
                assertNull(unchanged?.get("phone", String::class.java))
                assertEquals(LocalDate.now().minusYears(10),
                    unchanged?.get("date_of_birth", LocalDate::class.java))
                assertTrue(service.update(account, youth, MemberDetailsUpdate.Input(
                    "Adult", "Synthetic", LocalDate.of(1990, 1, 1),
                    "adult@example.invalid", "+41000000002")))
                db.execute("DELETE FROM club_capability_grants WHERE member_id = ? AND capability = 'EDIT_MEMBERS'",
                    editor)
                assertFalse(service.update(account, target, update.copy(firstName = "DeniedAfterRevoke")))
                assertEquals("Changed", db.fetchValue(
                    "SELECT first_name FROM members WHERE id = ?", target))
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", editor)
                db.execute("UPDATE members SET account_id = NULL WHERE id = ?", editor)
                assertFalse(service.update(account, target, update))
            } finally {
                db.execute("DELETE FROM members WHERE id IN (?, ?, ?)", editor, target, youth)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", account, outsider)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
