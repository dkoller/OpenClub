package org.openclub.api.auth

import org.openclub.api.database.*
import org.openclub.api.members.*
import org.openclub.api.*

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
            val accountId = UUID.randomUUID()
            db.execute(
                "INSERT INTO accounts (id, display_name) VALUES (?, ?)",
                accountId, "Synthetic session test person",
            )
            db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                UUID.randomUUID(), accountId, "session-test-" + accountId)
            try {
                test(AuthSessions(db), db, accountId)
            } finally {
                // FK ON DELETE CASCADE cleans up ONLY this test person's sessions.
                db.execute("DELETE FROM accounts WHERE id = ?", accountId)
            }
        }
    }

    @Test
    fun issuesHashedHandlesAndRequiresCorrectCsrfForMutations() = withStore { sessions, db, person ->
        val issued = sessions.create(person)
        assertEquals(43, issued.handle.length)
        assertEquals(43, issued.csrfToken.length)
        assertNotEquals(issued.handle, issued.csrfToken)
        assertEquals(1L, db.fetchValue("SELECT COUNT(*) FROM auth_sessions WHERE account_id = ?", person))
        val storedHash = db.fetchValue("SELECT session_hash FROM auth_sessions WHERE account_id = ?", person).toString()
        assertTrue(Regex("[0-9a-f]{64}").matches(storedHash))
        assertFalse(storedHash.contains(issued.handle))

        assertNull(sessions.find(null))
        assertNull(sessions.find("invalid"))
        assertNull(sessions.find("z".repeat(43)))
        assertEquals(ActiveSession(person, issued.csrfToken), sessions.find(issued.handle))
        db.execute(
            """UPDATE auth_sessions
               SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes',
                   idle_expires_at = CURRENT_TIMESTAMP + INTERVAL '45 seconds'
               WHERE account_id = ?""",
            person,
        )
        val before = requireNotNull(db.fetchOne(
            "SELECT last_used_at, idle_expires_at FROM auth_sessions WHERE account_id = ?", person,
        ))
        assertEquals(true, db.fetchValue(
            "SELECT idle_expires_at < CURRENT_TIMESTAMP + INTERVAL '1 minute' FROM auth_sessions WHERE account_id = ?",
            person,
        ))
        assertNull(sessions.findForMutation(issued.handle, null))
        assertNull(sessions.findForMutation(issued.handle, "incorrect"))
        assertNull(sessions.findForMutation(issued.handle, "x".repeat(43)))
        val afterRejected = requireNotNull(db.fetchOne(
            "SELECT last_used_at, idle_expires_at FROM auth_sessions WHERE account_id = ?", person,
        ))
        assertEquals(before.get("last_used_at"), afterRejected.get("last_used_at"))
        assertEquals(before.get("idle_expires_at"), afterRejected.get("idle_expires_at"))
        assertEquals(
            ActiveSession(person, issued.csrfToken),
            sessions.findForMutation(issued.handle, issued.csrfToken),
        )
        assertNotEquals(before.get("last_used_at"), db.fetchValue(
            "SELECT last_used_at FROM auth_sessions WHERE account_id = ?", person,
        ))
        assertEquals(true, db.fetchValue(
            """SELECT idle_expires_at BETWEEN
                   CURRENT_TIMESTAMP + INTERVAL '29 minutes' AND
                   CURRENT_TIMESTAMP + INTERVAL '31 minutes'
               FROM auth_sessions WHERE account_id = ?""",
            person,
        ))
    }

    @Test
    fun rotatesAtomicallyAndPreservesAbsoluteExpiry() = withStore { sessions, db, person ->
        val initial = sessions.create(person)
        val hardExpiry = db.fetchValue("SELECT absolute_expires_at FROM auth_sessions WHERE account_id = ?", person)
        val replacement = requireNotNull(sessions.rotate(initial.handle))
        assertNotEquals(initial.handle, replacement.handle)
        assertNotEquals(initial.csrfToken, replacement.csrfToken)
        assertNull(sessions.find(initial.handle))
        assertNull(sessions.rotate(initial.handle))
        assertNull(sessions.findForMutation(replacement.handle, initial.csrfToken))
        assertEquals(ActiveSession(person, replacement.csrfToken), sessions.find(replacement.handle))
        assertEquals(hardExpiry, db.fetchValue("SELECT absolute_expires_at FROM auth_sessions WHERE account_id = ?", person))
        assertEquals(1L, db.fetchValue("SELECT COUNT(*) FROM auth_sessions WHERE account_id = ?", person))
    }


    @Test
    fun validAccessSlidesIdleDeadlineButCannotPassAbsoluteExpiry() = withStore { sessions, db, person ->
        val issued = sessions.create(person)
        db.execute(
            "UPDATE auth_sessions SET idle_expires_at = CURRENT_TIMESTAMP + INTERVAL '45 seconds' WHERE account_id = ?",
            person,
        )
        assertNotNull(sessions.find(issued.handle))
        assertEquals(true, db.fetchValue(
            """SELECT idle_expires_at BETWEEN
                   CURRENT_TIMESTAMP + INTERVAL '29 minutes' AND
                   CURRENT_TIMESTAMP + INTERVAL '31 minutes'
               FROM auth_sessions WHERE account_id = ?""",
            person,
        ))

        // An otherwise valid session with only five minutes of hard lifetime left:
        // access MUST clamp its sliding idle deadline to the absolute deadline.
        db.execute(
            """UPDATE auth_sessions
               SET absolute_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                   idle_expires_at = CURRENT_TIMESTAMP + INTERVAL '45 seconds'
               WHERE account_id = ?""",
            person,
        )
        assertNotNull(sessions.find(issued.handle))
        assertEquals(true, db.fetchValue(
            "SELECT idle_expires_at = absolute_expires_at FROM auth_sessions WHERE account_id = ?",
            person,
        ))

        // Rotation must apply the identical hard clamp while changing both secrets.
        db.execute(
            "UPDATE auth_sessions SET idle_expires_at = CURRENT_TIMESTAMP + INTERVAL '45 seconds' WHERE account_id = ?",
            person,
        )
        val hardExpiry = db.fetchValue(
            "SELECT absolute_expires_at FROM auth_sessions WHERE account_id = ?", person,
        )
        val rotated = requireNotNull(sessions.rotate(issued.handle))
        assertNotEquals(issued.handle, rotated.handle)
        assertNotEquals(issued.csrfToken, rotated.csrfToken)
        assertNull(sessions.find(issued.handle))
        assertEquals(hardExpiry, db.fetchValue(
            "SELECT absolute_expires_at FROM auth_sessions WHERE account_id = ?", person,
        ))
        assertEquals(true, db.fetchValue(
            "SELECT idle_expires_at = absolute_expires_at FROM auth_sessions WHERE account_id = ?",
            person,
        ))
    }


    @Test
    fun enforcesIdleAndHardExpiryAndPrunesWithoutRevival() = withStore { sessions, db, person ->
        val idle = sessions.create(person)
        db.execute(
            "UPDATE auth_sessions SET idle_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE account_id = ?",
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
               WHERE account_id = ?""",
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
        assertEquals(1, sessions.revokeAllForAccount(person))
        assertNull(sessions.find(two.handle))
        assertEquals(0, sessions.revokeAllForAccount(person))
        val third = sessions.create(person)
        db.execute("DELETE FROM accounts WHERE id = ?", person)
        assertNull(sessions.find(third.handle))
        assertEquals(0L, db.fetchValue("SELECT COUNT(*) FROM auth_sessions WHERE account_id = ?", person))
    }

    @Test
    fun failedReplacementInsertRollsBackOldSessionRevocation() = withStore { sessions, db, person ->
        val original = sessions.create(person)
        // A non-existent person violates auth_sessions.account_id's foreign key.
        // The delete runs first; that deletion MUST roll back with the insert.
        val unknownPerson = UUID.randomUUID()
        assertThrows(Exception::class.java) {
            sessions.replace(original.handle, unknownPerson)
        }
        assertEquals(ActiveSession(person, original.csrfToken), sessions.find(original.handle))
        assertEquals(1L, db.fetchValue(
            "SELECT COUNT(*) FROM auth_sessions WHERE account_id = ?", person,
        ))
        val replacement = sessions.replace(original.handle, person)
        assertNull(sessions.find(original.handle))
        assertEquals(ActiveSession(person, replacement.csrfToken), sessions.find(replacement.handle))
        assertEquals(1L, db.fetchValue(
            "SELECT COUNT(*) FROM auth_sessions WHERE account_id = ?", person,
        ))
    }

    @Test
    fun logoutValidatesSessionAndCsrfBeforeDeleting() = withStore { sessions, db, person ->
        val issued = sessions.create(person)
        val before = db.fetchOne("SELECT last_used_at, idle_expires_at FROM auth_sessions WHERE account_id = ?", person)
        assertEquals(LogoutResult.UNAUTHENTICATED, sessions.logout(null, issued.csrfToken))
        assertEquals(LogoutResult.UNAUTHENTICATED, sessions.logout("x".repeat(43), issued.csrfToken))
        assertEquals(LogoutResult.FORBIDDEN, sessions.logout(issued.handle, null))
        assertEquals(LogoutResult.FORBIDDEN, sessions.logout(issued.handle, "x".repeat(43)))
        assertEquals(before, db.fetchOne("SELECT last_used_at, idle_expires_at FROM auth_sessions WHERE account_id = ?", person))
        assertEquals(LogoutResult.REVOKED, sessions.logout(issued.handle, issued.csrfToken))
        assertNull(sessions.find(issued.handle))
        assertEquals(LogoutResult.UNAUTHENTICATED, sessions.logout(issued.handle, issued.csrfToken))
        val expired = sessions.create(person)
        db.execute("UPDATE auth_sessions SET idle_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE account_id = ?", person)
        assertEquals(LogoutResult.UNAUTHENTICATED, sessions.logout(expired.handle, expired.csrfToken))
    }

    @Test
    fun concurrentLogoutHasExactlyOneWinner() = withStore { sessions, _, person ->
        val issued = sessions.create(person)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val attempts = (1..2).map {
                pool.submit(java.util.concurrent.Callable {
                    start.await()
                    sessions.logout(issued.handle, issued.csrfToken)
                })
            }
            start.countDown()
            val results = attempts.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == LogoutResult.REVOKED })
            assertEquals(1, results.count { it == LogoutResult.UNAUTHENTICATED })
            assertNull(sessions.find(issued.handle))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun revokedIdentityInvalidatesSessionReadsMutationsAndRotationWithoutSlidingExpiry() =
        withStore { sessions, db, accountId ->
            val issued = sessions.create(accountId)
            assertNotNull(sessions.find(issued.handle))
            db.execute("DELETE FROM account_identities WHERE account_id = ?", accountId)
            val before = db.fetchOne(
                "SELECT last_used_at, idle_expires_at FROM auth_sessions WHERE account_id = ?", accountId)
            assertNull(sessions.find(issued.handle))
            assertNull(sessions.findForMutation(issued.handle, issued.csrfToken))
            assertNull(sessions.rotate(issued.handle))
            assertEquals(before, db.fetchOne(
                "SELECT last_used_at, idle_expires_at FROM auth_sessions WHERE account_id = ?", accountId))
            // A revoked account may still explicitly log out its stale handle.
            assertEquals(LogoutResult.REVOKED, sessions.logout(issued.handle, issued.csrfToken))
            assertNull(sessions.find(issued.handle))
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
        assertThrows(Exception::class.java) {
            sessions.logout("a".repeat(43), "b".repeat(43))
        }
    }
}
