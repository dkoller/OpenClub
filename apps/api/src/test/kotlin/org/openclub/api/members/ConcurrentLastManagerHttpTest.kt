package org.openclub.api.members

import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.module
import java.util.UUID
import org.openclub.api.checkContract

/**
 * The last eligible permission manager can never be removed, even by racing HTTP requests (#24).
 * Eligible means: ADMIN role, club-wide MANAGE_PERMISSIONS grant, verified identity, not archived.
 * The SQL services have their own concurrency tests; this drives the real routes, so the HTTP
 * pre-check, the session lookup and the serialized transaction are all in the race.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentLastManagerHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val handles = listOf("a", "b", "c")
    private val accounts = handles.associateWith { UUID.randomUUID() }
    private val members = handles.associateWith { UUID.randomUUID() }
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? =
            accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    @BeforeAll
    fun createFixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val db = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        database = db
        try {
            runBlocking {
                db.onDatabaseThread { tx ->
                    tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)",
                        club, "Last manager race club", "last-manager-race-$club")
                    for (handle in handles) {
                        tx.execute("INSERT INTO accounts (id) VALUES (?)", accounts.getValue(handle))
                        tx.execute(
                            """INSERT INTO account_identities (id, account_id, issuer, subject)
                               VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                            UUID.randomUUID(), accounts.getValue(handle), UUID.randomUUID().toString())
                        tx.execute(
                            """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth,
                               email, phone, role) VALUES (?, ?, 'Synthetic', 'Person', DATE '1990-01-01',
                               'person@example.invalid', '+41000000000', 'MEMBER')""",
                            members.getValue(handle), accounts.getValue(handle))
                    }
                }
            }
        } catch (failure: Throwable) {
            dropFixture()
            throw failure
        }
    }

    @AfterAll
    fun dropFixture() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                for (handle in handles) tx.execute("DELETE FROM members WHERE id = ?", members.getValue(handle))
                for (handle in handles) tx.execute("DELETE FROM accounts WHERE id = ?", accounts.getValue(handle))
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    /** Exactly [managers] are eligible managers; everyone else is a plain member without grants. */
    private fun reset(managers: Set<String>) = runBlocking {
        requireNotNull(database).onDatabaseThread { tx ->
            for (handle in handles) {
                val id = members.getValue(handle)
                tx.execute("DELETE FROM club_capability_grants WHERE member_id = ?", id)
                tx.execute("UPDATE members SET role = ? WHERE id = ?",
                    if (handle in managers) "ADMIN" else "MEMBER", id)
                if (handle in managers) {
                    tx.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", id)
                }
            }
        }
    }

    private fun eligibleManagers(): Int = runBlocking {
        requireNotNull(database).onDatabaseThread { tx ->
            tx.fetchValue(
                """SELECT COUNT(*) FROM members m
                   WHERE m.id IN (?, ?, ?) AND m.role = 'ADMIN' AND m.archived_at IS NULL
                     AND EXISTS (SELECT 1 FROM club_capability_grants g
                                 WHERE g.member_id = m.id AND g.capability = 'MANAGE_PERMISSIONS')
                     AND EXISTS (SELECT 1 FROM account_identities i WHERE i.account_id = m.account_id)""",
                *handles.map { members.getValue(it) }.toTypedArray())?.toString()?.toInt() ?: -1
        }
    }

    private enum class Kind { REVOKE_GRANT, DEMOTE }

    private class Action(val actor: String, val kind: Kind, val target: String)

    private suspend fun ApplicationTestBuilder.perform(action: Action): Int {
        val target = members.getValue(action.target)
        val response = when (action.kind) {
            Kind.REVOKE_GRANT -> client.delete("/api/v1/grants") {
                header(HttpHeaders.Cookie, "${cookies.sessionName}=${action.actor}")
                header(HttpHeaders.Origin, origin)
                header("X-CSRF-Token", csrf)
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"memberId":"$target","capability":"MANAGE_PERMISSIONS","scope":"CLUB"}""")
            }
            Kind.DEMOTE -> client.patch("/api/v1/members/$target/role") {
                header(HttpHeaders.Cookie, "${cookies.sessionName}=${action.actor}")
                header(HttpHeaders.Origin, origin)
                header("X-CSRF-Token", csrf)
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"role":"MEMBER"}""")
            }
        }
        return response.status.value
    }

    private val rounds = 15

    /**
     * Every action removes one distinct manager. The serialized transaction lets an action succeed
     * only while another eligible manager would remain, so afterwards: at least one manager is
     * left, the count equals start minus successes, and every refused action was a clean 403.
     */
    private fun race(name: String, managers: Set<String>, actions: List<Action>) = testApplication {
        application { checkContract();
            module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin,
                database = requireNotNull(database))
        }
        val failures = mutableListOf<String>()
        repeat(rounds) { round ->
            reset(managers)
            check(eligibleManagers() == managers.size) { "fixture reset failed" }
            val start = CompletableDeferred<Unit>()
            val statuses = coroutineScope {
                val calls = actions.map { async { start.await(); perform(it) } }
                start.complete(Unit)
                calls.awaitAll()
            }
            val succeeded = statuses.count { it == 204 }
            val refused = statuses.count { it == 403 }
            val left = eligibleManagers()
            if (succeeded + refused != actions.size) failures += "$name round $round: statuses $statuses"
            if (left < 1) failures += "$name round $round: no eligible manager left (statuses $statuses)"
            if (left != managers.size - succeeded) {
                failures += "$name round $round: $left managers left after $succeeded successes (statuses $statuses)"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    @Test
    fun twoManagersRevokingEachOthersGrantLeaveExactlyOne() = race("revoke vs revoke", setOf("a", "b"),
        listOf(Action("a", Kind.REVOKE_GRANT, "b"), Action("b", Kind.REVOKE_GRANT, "a")))

    @Test
    fun twoManagersDemotingEachOtherLeaveExactlyOne() = race("demote vs demote", setOf("a", "b"),
        listOf(Action("a", Kind.DEMOTE, "b"), Action("b", Kind.DEMOTE, "a")))

    @Test
    fun aRevocationRacingADemotionLeavesExactlyOne() = race("revoke vs demote", setOf("a", "b"),
        listOf(Action("a", Kind.REVOKE_GRANT, "b"), Action("b", Kind.DEMOTE, "a")))

    @Test
    fun threeManagersRemovingEachOtherInACycleLeaveAtLeastOne() = race("cycle of three", setOf("a", "b", "c"),
        listOf(Action("a", Kind.REVOKE_GRANT, "b"), Action("b", Kind.DEMOTE, "c"), Action("c", Kind.REVOKE_GRANT, "a")))
}
