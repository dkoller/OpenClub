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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MemberRolesTest {
    @Test
    fun roleTransitionsPreserveAdminAndPermissionManager() {
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
            val admin = UUID.randomUUID()
            val second = UUID.randomUUID()
            val regular = UUID.randomUUID()
            val roles = MemberRoles(db)
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "roles-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", account, outsider)
                for (linkedAccount in listOf(account, outsider)) {
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), linkedAccount, UUID.randomUUID().toString())
                }
                for ((id, role, linked) in listOf(
                    Triple(admin, "ADMIN", true),
                    Triple(second, "ADMIN", false),
                    Triple(regular, "MEMBER", false),
                )) {
                    db.execute("""INSERT INTO members
                        (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                        VALUES (?, ?, 'Synthetic', 'Member', DATE '1990-01-01',
                                'synthetic@example.invalid', '+41000000000', ?)""",
                        id, if (linked) account else null, role)
                }
                assertEquals(MemberRoles.Change.DENIED,
                    roles.change(account, regular, MemberRoles.Role.ADMIN))
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", admin)
                assertEquals(MemberRoles.Change.DENIED,
                    roles.change(outsider, regular, MemberRoles.Role.ADMIN))
                assertEquals(MemberRoles.Change.DENIED,
                    roles.change(account, admin, MemberRoles.Role.MEMBER))
                assertEquals(MemberRoles.Change.UNCHANGED,
                    roles.change(account, second, MemberRoles.Role.ADMIN))
                assertEquals(MemberRoles.Change.UPDATED,
                    roles.change(account, second, MemberRoles.Role.MEMBER))
                // Revoking the actor's explicit grant must immediately deny role mutation.
                db.execute("""DELETE FROM club_capability_grants
                    WHERE member_id = ? AND capability = 'MANAGE_PERMISSIONS'""", admin)
                assertEquals(MemberRoles.Change.DENIED,
                    roles.change(account, second, MemberRoles.Role.ADMIN))
                assertEquals("MEMBER", db.fetchValue(
                    "SELECT role FROM members WHERE id = ?", second))
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", admin)
                // An account with a grant but no verified identity must also fail closed.
                db.execute("DELETE FROM account_identities WHERE account_id = ?", account)
                assertEquals(MemberRoles.Change.DENIED,
                    roles.change(account, second, MemberRoles.Role.ADMIN))
                assertEquals("MEMBER", db.fetchValue(
                    "SELECT role FROM members WHERE id = ?", second))
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                    VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                    UUID.randomUUID(), account, UUID.randomUUID().toString())
                assertEquals(MemberRoles.Change.DENIED,
                    roles.change(account, admin, MemberRoles.Role.MEMBER))
                assertEquals(MemberRoles.Change.UPDATED,
                    roles.change(account, regular, MemberRoles.Role.ADMIN))
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')",
                    regular)
                assertEquals(MemberRoles.Change.UPDATED,
                    roles.change(account, regular, MemberRoles.Role.MEMBER))
                assertEquals(0, db.fetchValue(
                    """SELECT COUNT(*) FROM club_capability_grants
                       WHERE member_id = ? AND capability = 'MANAGE_PERMISSIONS'""",
                    regular)?.toString()?.toInt())
                // Even a stale permission grant must not wake up on promotion.
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')",
                    regular)
                assertEquals(MemberRoles.Change.UPDATED,
                    roles.change(account, regular, MemberRoles.Role.ADMIN))
                assertEquals(0, db.fetchValue(
                    """SELECT COUNT(*) FROM club_capability_grants
                       WHERE member_id = ? AND capability = 'MANAGE_PERMISSIONS'""",
                    regular)?.toString()?.toInt())
                assertEquals(MemberRoles.Change.UPDATED,
                    roles.change(account, regular, MemberRoles.Role.MEMBER))
                assertEquals(MemberRoles.Change.DENIED,
                    roles.change(account, UUID.randomUUID(), MemberRoles.Role.ADMIN))

                db.execute("UPDATE members SET role = 'ADMIN' WHERE id = ?", second)
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", second)
                // Link the second administrator before testing eligible-manager demotion.
                db.execute("UPDATE members SET account_id = ? WHERE id = ?", outsider, second)
                assertEquals(MemberRoles.Change.UPDATED,
                    roles.change(account, second, MemberRoles.Role.MEMBER))
                assertEquals(MemberRoles.Change.DENIED,
                    roles.change(account, admin, MemberRoles.Role.MEMBER))
                db.execute("UPDATE members SET account_id = NULL WHERE id = ?", admin)
                assertEquals(MemberRoles.Change.DENIED,
                    roles.change(account, regular, MemberRoles.Role.ADMIN))
            } finally {
                db.execute("DELETE FROM members WHERE id IN (?, ?, ?)", admin, second, regular)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", account, outsider)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }

    @Test
    fun concurrentReciprocalDemotionsKeepAnAdministratorAndPermissionManager() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 4
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val club = UUID.randomUUID()
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val memberA = UUID.randomUUID()
            val memberB = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "race-roles-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", a, b)
                for (linkedAccount in listOf(a, b)) {
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), linkedAccount, UUID.randomUUID().toString())
                }
                for ((id, account) in listOf(memberA to a, memberB to b)) {
                    db.execute("""INSERT INTO members
                        (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                        VALUES (?, ?, 'Synthetic', 'Admin', DATE '1990-01-01',
                                'synthetic@example.invalid', '+41000000000', 'ADMIN')""",
                        id, account)
                    db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", id)
                }
                val ready = CountDownLatch(2)
                val go = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val tasks = listOf(a to memberB, b to memberA).map { (actor, target) ->
                        pool.submit<MemberRoles.Change> {
                            ready.countDown()
                            go.await()
                            MemberRoles(DSL.using(source, SQLDialect.POSTGRES)).change(
                                actor, target, MemberRoles.Role.MEMBER)
                        }
                    }
                    assertTrue(ready.await(10, TimeUnit.SECONDS))
                    go.countDown()
                    assertEquals(
                        setOf(MemberRoles.Change.UPDATED, MemberRoles.Change.DENIED),
                        tasks.map { it.get(15, TimeUnit.SECONDS) }.toSet())
                    assertEquals(1, db.fetchValue(
                        "SELECT COUNT(*) FROM members WHERE role = 'ADMIN'")?.toString()?.toInt())
                    assertEquals(1, db.fetchValue(
                        """SELECT COUNT(*) FROM members m
                           JOIN club_capability_grants g ON g.member_id = m.id
                           WHERE m.role = 'ADMIN' AND g.capability = 'MANAGE_PERMISSIONS'"""
                    )?.toString()?.toInt())
                } finally {
                    go.countDown()
                    pool.shutdownNow()
                }
            } finally {
                db.execute("DELETE FROM members WHERE id IN (?, ?)", memberA, memberB)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", a, b)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
