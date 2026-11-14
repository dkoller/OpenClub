package org.openclub.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthSessionsTest {
    private fun withStore(test: (AuthSessions, DSLContext, UUID) -> Unit) {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 2
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val personId = UUID.randomUUID()
            db.execute(
                "INSERT INTO persons (id, display_name) VALUES (?, ?)",
                personId, "Synthetic session test person",
            )
            try {
                test(AuthSessions(db), db, personId)
            } finally {
                // FK ON DELETE CASCADE cleans up ONLY this test person's sessions.
                db.execute("DELETE FROM persons WHERE id = ?", personId)
            }
        }
    }

    @Test
    fun issuesHashedHandlesAndRequiresCorrectCsrfForMutations() = withStore { sessions, db, person ->
        val issued = sessions.create(person)
        assertEquals(43, issued.handle.length)
        assertEquals(43, issued.csrfToken.length)
        assertNotEquals(issued.handle, issued.csrfToken)
        assertEquals(1L, db.fetchValue("SELECT COUNT(*) FROM auth_sessions WHERE person_id = ?", person))
        val storedHash = db.fetchValue("SELECT session_hash FROM auth_sessions WHERE person_id = ?", person).toString()
        assertTrue(Regex("[0-9a-f]{64}").matches(storedHash))
        assertFalse(storedHash.contains(issued.handle))

        assertNull(sessions.find(null))
        assertNull(sessions.find("invalid"))
        assertNull(sessions.find("z".repeat(43)))
        assertEquals(ActiveSession(person, issued.csrfToken), sessions.find(issued.handle))
        db.execute(
            "UPDATE auth_sessions SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes' WHERE person_id = ?",
            person,
        )
        val before = db.fetchValue("SELECT last_used_at FROM auth_sessions WHERE person_id = ?", person)
        assertNull(sessions.findForMutation(issued.handle, null))
        assertNull(sessions.findForMutation(issued.handle, "incorrect"))
        assertNull(sessions.findForMutation(issued.handle, "x".repeat(43)))
        assertEquals(before, db.fetchValue("SELECT last_used_at FROM auth_sessions WHERE person_id = ?", person))
        assertEquals(
            ActiveSession(person, issued.csrfToken),
            sessions.findForMutation(issued.handle, issued.csrfToken),
        )
        assertNotEquals(before, db.fetchValue("SELECT last_used_at FROM auth_sessions WHERE person_id = ?", person))
    }

    @Test
    fun rotatesAtomicallyAndPreservesAbsoluteExpiry() = withStore { sessions, db, person ->
        val initial = sessions.create(person)
        val hardExpiry = db.fetchValue("SELECT absolute_expires_at FROM auth_sessions WHERE person_id = ?", person)
        val replacement = requireNotNull(sessions.rotate(initial.handle))
        assertNotEquals(initial.handle, replacement.handle)
        assertNotEquals(initial.csrfToken, replacement.csrfToken)
        assertNull(sessions.find(initial.handle))
        assertNull(sessions.rotate(initial.handle))
        assertNull(sessions.findForMutation(replacement.handle, initial.csrfToken))
        assertEquals(ActiveSession(person, replacement.csrfToken), sessions.find(replacement.handle))
        assertEquals(hardExpiry, db.fetchValue("SELECT absolute_expires_at FROM auth_sessions WHERE person_id = ?", person))
        assertEquals(1L, db.fetchValue("SELECT COUNT(*) FROM auth_sessions WHERE person_id = ?", person))
    }

    @Test
    fun enforcesIdleAndHardExpiryAndPrunesWithoutRevival() = withStore { sessions, db, person ->
        val idle = sessions.create(person)
        db.execute(
            "UPDATE auth_sessions SET idle_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE person_id = ?",
            person,
        )
        assertNull(sessions.find(idle.handle))
        assertNull(sessions.rotate(idle.handle))
        assertEquals(1, sessions.pruneExpired())
        assertNull(sessions.find(idle.handle))

        val absolute = sessions.create(person)
        db.execute(
            """UPDATE auth_sessions
               SET created_at = CURRENT_TIMESTAMP - INTERVAL '13 hours',
                   last_used_at = CURRENT_TIMESTAMP - INTERVAL '13 hours',
                   idle_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second',
                   absolute_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
               WHERE person_id = ?""",
            person,
        )
        assertNull(sessions.find(absolute.handle))
        assertNull(sessions.rotate(absolute.handle))
        assertEquals(1, sessions.pruneExpired())
    }

    @Test
    fun revocationAndPersonDeletionPreventReuse() = withStore { sessions, db, person ->
        val one = sessions.create(person)
        val two = sessions.create(person)
        assertTrue(sessions.revoke(one.handle))
        assertFalse(sessions.revoke(one.handle))
        assertNull(sessions.find(one.handle))
        assertNotNull(sessions.find(two.handle))
        assertEquals(1, sessions.revokeAllForPerson(person))
        assertNull(sessions.find(two.handle))
        assertEquals(0, sessions.revokeAllForPerson(person))
        val third = sessions.create(person)
        db.execute("DELETE FROM persons WHERE id = ?", person)
        assertNull(sessions.find(third.handle))
        assertEquals(0L, db.fetchValue("SELECT COUNT(*) FROM auth_sessions WHERE person_id = ?", person))
    }

    @Test
    fun databaseOutageFailsClosedInsteadOfGrantingAccess() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        val source = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
        })
        val sessions = AuthSessions(DSL.using(source, SQLDialect.POSTGRES))
        source.close()
        assertThrows(Exception::class.java) {
            sessions.find("a".repeat(43))
        }
    }
}
