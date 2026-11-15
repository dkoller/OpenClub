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

class CapabilityGrantsTest {
    @Test
    fun permissionManagerCanGrantAndRevokeWithoutSelfEscalationOrLastManagerRemoval() {
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
            val account = UUID.randomUUID()
            val outsider = UUID.randomUUID()
            val manager = UUID.randomUUID()
            val secondAdmin = UUID.randomUUID()
            val member = UUID.randomUUID()
            val juniors = UUID.randomUUID()
            val adults = UUID.randomUUID()
            val grants = CapabilityGrants(db)
            val clubScope = CapabilityGrants.Scope.CLUB
            val teamScope = CapabilityGrants.Scope.TEAM
            val managePermissions = ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS
            val manageTeams = ClubCapabilityAccess.Capability.MANAGE_TEAMS
            val directory = ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "grants-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", account, outsider)
                for (linkedAccount in listOf(account, outsider)) {
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), linkedAccount, UUID.randomUUID().toString())
                }
                db.execute("""INSERT INTO members
                    (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                    VALUES (?, ?, 'First', 'Admin', DATE '1990-01-01',
                            'first@example.invalid', '+41000000000', 'ADMIN')""",
                    manager, account)
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth, email, phone, role)
                    VALUES (?, 'Second', 'Admin', DATE '1990-01-01',
                            'second@example.invalid', '+41000000000', 'ADMIN')""", secondAdmin)
                db.execute("""INSERT INTO members
                    (id, first_name, last_name, date_of_birth, email, phone)
                    VALUES (?, 'Regular', 'Member', DATE '1990-01-01',
                            'member@example.invalid', '+41000000000')""", member)
                db.execute("INSERT INTO teams (id, name) VALUES (?, 'Juniors'), (?, 'Adults')",
                    juniors, adults)
                assertEquals(CapabilityGrants.Change.DENIED,
                    grants.grant(account, member, directory, clubScope))
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')",
                    manager)
                assertEquals(CapabilityGrants.Change.DENIED,
                    grants.grant(outsider, member, directory, clubScope))
                assertEquals(CapabilityGrants.Change.DENIED,
                    grants.grant(account, manager, manageTeams, clubScope))
                assertEquals(CapabilityGrants.Change.DENIED,
                    grants.grant(account, member, managePermissions, clubScope))
                assertEquals(CapabilityGrants.Change.DENIED,
                    grants.revoke(account, manager, managePermissions, clubScope))
                assertEquals(CapabilityGrants.Change.GRANTED,
                    grants.grant(account, member, directory, clubScope))
                assertEquals(CapabilityGrants.Change.UNCHANGED,
                    grants.grant(account, member, directory, clubScope))
                assertTrue(ClubCapabilityAccess(db).hasForTeam(account, juniors, manageTeams).not())
                assertEquals(CapabilityGrants.Change.GRANTED,
                    grants.grant(account, member, manageTeams, teamScope, juniors))
                assertEquals(CapabilityGrants.Change.DENIED,
                    grants.grant(account, member, manageTeams, teamScope, UUID.randomUUID()))
                // An accountless ADMIN cannot serve as a recoverable manager.
                assertEquals(CapabilityGrants.Change.DENIED,
                    grants.grant(account, secondAdmin, managePermissions, clubScope))
                db.execute("UPDATE members SET account_id = ? WHERE id = ?", outsider, secondAdmin)
                assertEquals(CapabilityGrants.Change.GRANTED,
                    grants.grant(account, secondAdmin, managePermissions, clubScope))
                assertEquals(CapabilityGrants.Change.REVOKED,
                    grants.revoke(account, secondAdmin, managePermissions, clubScope))
                assertEquals(CapabilityGrants.Change.DENIED,
                    grants.revoke(account, manager, managePermissions, clubScope))
                assertEquals(CapabilityGrants.Change.REVOKED,
                    grants.revoke(account, member, manageTeams, teamScope, juniors))
                assertEquals(CapabilityGrants.Change.REVOKED,
                    grants.revoke(account, member, directory, clubScope))
                assertEquals(CapabilityGrants.Change.UNCHANGED,
                    grants.revoke(account, member, directory, clubScope))
                assertThrows(IllegalArgumentException::class.java) {
                    grants.grant(account, member, directory, teamScope, juniors)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    grants.grant(account, member, manageTeams, clubScope, juniors)
                }
                db.execute("UPDATE members SET role = 'MEMBER' WHERE id = ?", manager)
                assertEquals(CapabilityGrants.Change.DENIED,
                    grants.grant(account, member, directory, clubScope))
            } finally {
                db.execute("DELETE FROM teams WHERE id IN (?, ?)", juniors, adults)
                db.execute("DELETE FROM members WHERE id IN (?, ?, ?)", manager, secondAdmin, member)
                db.execute("DELETE FROM accounts WHERE id IN (?, ?)", account, outsider)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }

    @Test
    fun concurrentGrantRequestsCreateOneRowAndDoNotDeadlock() {
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
            val actorAccount = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val target = UUID.randomUUID()
            try {
                db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                    club, "race-grants-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?)", actorAccount)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                    VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                    UUID.randomUUID(), actorAccount, UUID.randomUUID().toString())
                for ((id, isAdmin) in listOf(actor to true, target to false)) {
                    db.execute("""INSERT INTO members
                        (id, account_id, first_name, last_name, date_of_birth,
                        email, phone, role) VALUES
                        (?, ?, 'Synthetic', 'Member', DATE '1990-01-01',
                        'synthetic@example.invalid', '+41000000000', ?)""",
                        id, if (isAdmin) actorAccount else null,
                        if (isAdmin) "ADMIN" else "MEMBER")
                }
                db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')",
                    actor)
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val tasks = (1..2).map {
                        pool.submit<CapabilityGrants.Change> {
                            ready.countDown()
                            start.await()
                            CapabilityGrants(DSL.using(source, SQLDialect.POSTGRES)).grant(
                                actorAccount, target,
                                ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY,
                                CapabilityGrants.Scope.CLUB)
                        }
                    }
                    assertTrue(ready.await(10, TimeUnit.SECONDS))
                    start.countDown()
                    assertEquals(setOf(
                        CapabilityGrants.Change.GRANTED, CapabilityGrants.Change.UNCHANGED),
                        tasks.map { it.get(15, TimeUnit.SECONDS) }.toSet())
                    assertEquals(1, db.fetchValue(
                        """SELECT COUNT(*) FROM club_capability_grants
                           WHERE member_id = ? AND capability = 'VIEW_MEMBER_DIRECTORY'""",
                        target)?.toString()?.toInt())
                } finally {
                    start.countDown()
                    pool.shutdownNow()
                }
            } finally {
                db.execute("DELETE FROM members WHERE id IN (?, ?)", actor, target)
                db.execute("DELETE FROM accounts WHERE id = ?", actorAccount)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
    @Test
    fun competingManagersCannotRevokeEachOtherAndLeaveNobodyAuthorized() {
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
                    club, "mutual-" + club)
                db.execute("INSERT INTO accounts (id) VALUES (?), (?)", a, b)
                for (linkedAccount in listOf(a, b)) {
                    db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                        VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                        UUID.randomUUID(), linkedAccount, UUID.randomUUID().toString())
                }
                for ((member, account) in listOf(memberA to a, memberB to b)) {
                    db.execute("""INSERT INTO members
                        (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                        VALUES (?, ?, 'Synthetic', 'Admin', DATE '1990-01-01',
                                'synthetic@example.invalid', '+41000000000', 'ADMIN')""",
                        member, account)
                    db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')",
                        member)
                }
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val attempted = listOf(a to memberB, b to memberA).map { (actor, target) ->
                        pool.submit<CapabilityGrants.Change> {
                            ready.countDown()
                            start.await()
                            CapabilityGrants(DSL.using(source, SQLDialect.POSTGRES)).revoke(
                                actor, target, ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS,
                                CapabilityGrants.Scope.CLUB)
                        }
                    }
                    assertTrue(ready.await(10, TimeUnit.SECONDS))
                    start.countDown()
                    assertEquals(
                        setOf(CapabilityGrants.Change.REVOKED, CapabilityGrants.Change.DENIED),
                        attempted.map { it.get(15, TimeUnit.SECONDS) }.toSet(),
                    )
                    assertEquals(1, db.fetchValue(
                        """SELECT COUNT(*) FROM club_capability_grants
                           WHERE capability = 'MANAGE_PERMISSIONS'""")?.toString()?.toInt())
                } finally {
                    start.countDown()
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
