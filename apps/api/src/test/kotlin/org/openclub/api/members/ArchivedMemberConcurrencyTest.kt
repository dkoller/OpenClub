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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArchivedMemberConcurrencyTest {
    @Test
    fun aGrantWaitingOnAnArchivalRowLockCannotReactivateTheMember() {
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
            val member = UUID.randomUUID()
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Synthetic', ?)",
                club, "archival-race-" + club)
            try {
                db.execute(
                    """INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone)
                       VALUES (?, 'Synthetic', 'Member', DATE '1990-01-01',
                               'member@example.invalid', '+41000000000')""",
                    member,
                )
                val rowLocked = CountDownLatch(1)
                val release = CountDownLatch(1)
                val contenderReady = CountDownLatch(1)
                val contenderPid = AtomicInteger()
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val archive = executor.submit {
                        db.transaction { configuration ->
                            val tx = DSL.using(configuration)
                            check(tx.fetchOne("SELECT id FROM members WHERE id = ? FOR UPDATE",
                                member) != null)
                            rowLocked.countDown()
                            check(release.await(15, TimeUnit.SECONDS))
                            check(tx.execute(
                                "UPDATE members SET archived_at = CURRENT_TIMESTAMP WHERE id = ?",
                                member,
                            ) == 1)
                        }
                    }
                    assertTrue(rowLocked.await(10, TimeUnit.SECONDS))
                    val insert = executor.submit<Boolean> {
                        try {
                            db.transaction { configuration ->
                                val tx = DSL.using(configuration)
                                contenderPid.set(requireNotNull(
                                    tx.fetchValue("SELECT pg_backend_pid()", Int::class.java)))
                                contenderReady.countDown()
                                tx.execute(
                                    """INSERT INTO club_capability_grants (member_id, capability)
                                       VALUES (?, 'VIEW_TEAMS')""",
                                    member,
                                )
                            }
                            false
                        } catch (_: DataAccessException) {
                            true
                        }
                    }
                    assertTrue(contenderReady.await(10, TimeUnit.SECONDS))
                    // Observe the competing PostgreSQL transaction actually waiting
                    // for the row lock, rather than assuming the thread started.
                    var waiting = false
                    for (attempt in 1..200) {
                        if (db.fetchValue(
                                "SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?",
                                contenderPid.get(),
                            ) == "Lock") {
                            waiting = true
                            break
                        }
                        Thread.sleep(20)
                    }
                    assertTrue(waiting, "Competing insert never reached the database row lock")
                    release.countDown()
                    archive.get(10, TimeUnit.SECONDS)
                    assertTrue(insert.get(10, TimeUnit.SECONDS),
                        "Grant insertion after archival must fail")
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
                }
                assertNotNull(db.fetchValue(
                    "SELECT archived_at FROM members WHERE id = ?", member))
                assertEquals(0, db.fetchValue(
                    "SELECT COUNT(*) FROM club_capability_grants WHERE member_id = ?",
                    member,
                )?.toString()?.toInt())
            } finally {
                db.execute("DELETE FROM members WHERE id = ?", member)
                db.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
    }
}
