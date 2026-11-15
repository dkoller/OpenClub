package org.openclub.api.auth

import org.openclub.api.auth.*
import org.openclub.api.database.*
import org.openclub.api.members.*
import org.openclub.api.*

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VerifiedAccountsTest {
    private val issuer = "https://identity.example/realms/openclub"

    private fun resolver(db: DSLContext) = VerifiedAccounts(
        db,
        OidcSettings(
            URI(issuer),
            URI("$issuer/protocol/openid-connect/auth"),
            "openclub-bff",
            URI("https://app.example/api/v1/auth/callback"),
        ),
    )

    private fun withDatabase(test: (DSLContext) -> Unit) {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 8
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            test(DSL.using(source, SQLDialect.POSTGRES))
        }
    }

    @Test
    fun verifiedLoginNeverClaimsAnExistingClubMember() = withDatabase { db ->
        val existing = UUID.randomUUID()
        val club = UUID.randomUUID()
        val membership = UUID.randomUUID()
        val identity = VerifiedOidcIdentity(issuer, "synthetic-" + UUID.randomUUID())
        var resolved: UUID? = null
        try {
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)",
                club, "Synthetic club", "club-" + club)
            db.execute("INSERT INTO accounts (id, display_name) VALUES (?, ?)",
                existing, "Unlinked OpenClub account")
            db.execute("INSERT INTO members (id, club_id, account_id, first_name, last_name, date_of_birth, email, phone, role) VALUES (?, ?, ?, 'Sample', 'Admin', DATE '1990-01-01', 'admin@example.invalid', '+41000000000', ?)",
                membership, club, existing, "ADMIN")

            resolved = resolver(db).resolveOrCreate(identity)
            assertNotEquals(existing, resolved)
            assertEquals(resolved, resolver(db).resolveOrCreate(identity))
            assertEquals(1L, db.fetchValue(
                "SELECT COUNT(*) FROM account_identities WHERE issuer = ? AND subject = ?",
                identity.issuer, identity.subject,
            ))
            assertEquals(0L, db.fetchValue(
                "SELECT COUNT(*) FROM members WHERE account_id = ?", resolved,
            ))
            assertEquals("ADMIN", db.fetchValue(
                "SELECT role FROM members WHERE account_id = ? AND club_id = ?",
                existing, club,
            ))
        } finally {
            db.execute("DELETE FROM members WHERE id = ?", membership)
            if (resolved != null) db.execute("DELETE FROM accounts WHERE id = ?", resolved)
            db.execute("DELETE FROM accounts WHERE id = ?", existing)
            db.execute("DELETE FROM clubs WHERE id = ?", club)
        }
    }

    @Test
    fun subjectsAreCaseSensitiveAndIssuerIsPinned() = withDatabase { db ->
        val suffix = UUID.randomUUID().toString()
        val lowercase = VerifiedOidcIdentity(issuer, "a-$suffix")
        val uppercase = VerifiedOidcIdentity(issuer, "A-$suffix")
        val identities = resolver(db)
        val created = mutableListOf<UUID>()
        try {
            created.add(identities.resolveOrCreate(lowercase))
            created.add(identities.resolveOrCreate(uppercase))
            assertNotEquals(created[0], created[1])
            assertEquals(created[0], identities.resolveOrCreate(lowercase))
            assertThrows(IllegalArgumentException::class.java) {
                identities.resolveOrCreate(
                    VerifiedOidcIdentity("https://attacker.example/realm", lowercase.subject),
                )
            }
            for (subject in listOf("", " ", "a\nb", "\u0000", "日本", "x".repeat(256))) {
                assertThrows(IllegalArgumentException::class.java) {
                    identities.resolveOrCreate(VerifiedOidcIdentity(issuer, subject))
                }
            }
        } finally {
            created.forEach { db.execute("DELETE FROM accounts WHERE id = ?", it) }
        }
    }

    @Test
    fun simultaneousFirstLoginsProduceOneAccountAndOneBinding() = withDatabase { db ->
        val identity = VerifiedOidcIdentity(issuer, "concurrent-" + UUID.randomUUID())
        val before = db.fetchValue(
            "SELECT COUNT(*) FROM accounts WHERE display_name = 'Unlinked OpenClub account'"
        ) as Long
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val bothCandidatesInserted = CountDownLatch(2)
        val insertedCandidates = AtomicInteger()
        val attemptedBindings = AtomicInteger()
        val deletedLosingCandidates = AtomicInteger()

        // Test-only jOOQ listener: pause AFTER the real PostgreSQL accounts INSERT
        // succeeds in each uncommitted transaction, BEFORE either identity INSERT.
        // This forces the ON CONFLICT race instead of depending on thread scheduling.
        val listener = object : ExecuteListener {
            override fun executeStart(ctx: ExecuteContext) {
                if (ctx.sql()?.trimStart()?.uppercase()?.startsWith("INSERT INTO ACCOUNT_IDENTITIES ") == true) {
                    attemptedBindings.incrementAndGet()
                }
            }

            override fun executeEnd(ctx: ExecuteContext) {
                val sql = ctx.sql()?.trimStart()?.uppercase().orEmpty()
                if (sql.startsWith("INSERT INTO ACCOUNTS ")) {
                    insertedCandidates.incrementAndGet()
                    bothCandidatesInserted.countDown()
                    check(bothCandidatesInserted.await(10, TimeUnit.SECONDS)) {
                        "Both concurrent transactions must insert candidates before identity binding"
                    }
                } else if (sql.startsWith("DELETE FROM ACCOUNTS ")) {
                    deletedLosingCandidates.incrementAndGet()
                }
            }
        }
        val competingDb = DSL.using(
            db.configuration().derive(DefaultExecuteListenerProvider(listener))
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit<UUID> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    resolver(competingDb).resolveOrCreate(identity)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1])
            assertEquals(2, insertedCandidates.get(), "Both candidate rows must have been inserted")
            assertEquals(2, attemptedBindings.get(), "Both transactions must compete for the identity")
            assertEquals(1, deletedLosingCandidates.get(), "The loser must remove its orphan candidate")
            assertEquals(1L, db.fetchValue(
                "SELECT COUNT(*) FROM account_identities WHERE issuer = ? AND subject = ?",
                identity.issuer, identity.subject,
            ))
            assertEquals(before + 1, db.fetchValue(
                "SELECT COUNT(*) FROM accounts WHERE display_name = 'Unlinked OpenClub account'"
            ))
        } finally {
            start.countDown()
            pool.shutdownNow()
            val person = db.fetchOne(
                "SELECT account_id FROM account_identities WHERE issuer = ? AND subject = ?",
                identity.issuer, identity.subject,
            )?.get("account_id", UUID::class.java)
            if (person != null) db.execute("DELETE FROM accounts WHERE id = ?", person)
        }
    }
}
