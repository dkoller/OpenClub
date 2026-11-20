package org.openclub.api

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID

/**
 * Every private route, one holder with every club grant (#24):
 *  1. the fixture really is authorized on each route (so the denials below are not vacuous),
 *  2. once the verified identity is revoked, a still-valid session is denied (403) everywhere,
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PrivateRoutesRevocationTest {
    private var database: DatabaseRuntime? = null
    private val holderAccount = UUID.randomUUID()
    private val holder = UUID.randomUUID()
    private val target = UUID.randomUUID()
    /** Archived and unlinked, so the authorized pass really erases it (204). */
    private val erasable = UUID.randomUUID()
    private val team = UUID.randomUUID()
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? =
            if (handle == "holder") ActiveSession(holderAccount, csrf) else null
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
                    fun member(id: UUID, account: UUID?, role: String) {
                        if (account != null) {
                            tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                            tx.execute(
                                """INSERT INTO account_identities (id, account_id, issuer, subject)
                                   VALUES (?, ?, 'https://issuer.example.invalid', ?)""",
                                UUID.randomUUID(), account, UUID.randomUUID().toString())
                        }
                        tx.execute(
                            """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth,
                               email, phone, role) VALUES (?, ?, 'Synthetic', 'Person', DATE '1990-01-01',
                               'person@example.invalid', '+41000000000', ?)""", id, account, role)
                    }
                    tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)",
                        club, "Revocation matrix club", "revocation-matrix-$club")
                    member(holder, holderAccount, "ADMIN")
                    member(target, null, "MEMBER")
                    insertErasable(tx)
                    tx.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team,
                        "Revocation team ${UUID.randomUUID().toString().take(8)}")
                    for (capability in listOf("VIEW_MEMBER_DIRECTORY", "EDIT_MEMBERS", "MANAGE_PERMISSIONS",
                        "VIEW_TEAMS", "MANAGE_TEAMS", "MANAGE_FEES", "POST_ANNOUNCEMENTS")) {
                        tx.execute("INSERT INTO club_capability_grants VALUES (?, ?)", holder, capability)
                    }
                }
            }
        } catch (failure: Throwable) {
            dropFixture()
            throw failure
        }
    }

    private fun insertErasable(tx: DSLContext) = tx.execute(
        """INSERT INTO members (id, first_name, last_name, date_of_birth, archived_at)
           VALUES (?, 'Erasable', 'Person', DATE '2014-01-01', now()) ON CONFLICT DO NOTHING""", erasable)

    @AfterAll
    fun dropFixture() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx: DSLContext ->
                // Also the team the authorized pass created through POST /teams: other suites count teams.
                tx.execute("DELETE FROM teams WHERE id = ? OR name LIKE 'Revocation%'", team)
                // And the fee period the authorized pass created through POST /fee-periods.
                tx.execute("DELETE FROM fee_periods WHERE name LIKE 'Revocation%'")
                tx.execute("DELETE FROM announcements WHERE title LIKE 'Revocation%'")
                tx.execute("DELETE FROM members WHERE first_name = 'Denied' AND last_name = 'Person'")
                tx.execute("DELETE FROM audit_events WHERE target_member_id = ?", erasable)
                tx.execute("DELETE FROM members WHERE id IN (?, ?, ?)", holder, target, erasable)
                tx.execute("DELETE FROM accounts WHERE id = ?", holderAccount)
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private class Route(val method: HttpMethod, val path: String, val body: String? = null,
                        val allowedWhenAuthorized: Set<Int> = setOf(200, 201, 204, 409))

    /** Every private route; none is expected to change data. Bodies are valid but harmless. */
    private fun routes(): List<Route> = listOf(
        Route(HttpMethod.Get, "/api/v1/club"),
        Route(HttpMethod.Patch, "/api/v1/club", """{"name":"Revocation matrix club"}"""),
        Route(HttpMethod.Patch, "/api/v1/club/player-gender-policy", """{"genderRequiredForPlayers":false}"""),
        Route(HttpMethod.Patch, "/api/v1/club/adult-age-policy", """{"adultAgeYears":18}"""),
        Route(HttpMethod.Get, "/api/v1/members"),
        Route(HttpMethod.Get, "/api/v1/members/archived"),
        Route(HttpMethod.Get, "/api/v1/members/$target"),
        Route(HttpMethod.Patch, "/api/v1/members/$target", """{"firstName":"Synthetic"}"""),
        // The same values the fixture stored, so the authorized pass changes nothing visible.
        Route(HttpMethod.Put, "/api/v1/members/$target", """{"firstName":"Synthetic","lastName":"Person","dateOfBirth":"1990-01-01","email":"person@example.invalid","phone":"+41000000000"}"""),
        Route(HttpMethod.Post, "/api/v1/members",
            """{"firstName":"Denied","lastName":"Person","dateOfBirth":"2013-01-01"}"""),
        Route(HttpMethod.Get, "/api/v1/members/$target/role"),
        Route(HttpMethod.Patch, "/api/v1/members/$target/role", """{"role":"MEMBER"}"""),
        Route(HttpMethod.Get, "/api/v1/grants?memberId=$target"),
        Route(HttpMethod.Post, "/api/v1/grants",
            """{"memberId":"$target","capability":"VIEW_MEMBER_DIRECTORY","scope":"CLUB"}"""),
        Route(HttpMethod.Delete, "/api/v1/grants",
            """{"memberId":"$target","capability":"VIEW_MEMBER_DIRECTORY","scope":"CLUB"}"""),
        Route(HttpMethod.Get, "/api/v1/teams"),
        Route(HttpMethod.Post, "/api/v1/teams", """{"name":"Revocation matrix team"}"""),
        Route(HttpMethod.Patch, "/api/v1/teams/$team", """{"name":"Revocation matrix renamed"}"""),
        Route(HttpMethod.Get, "/api/v1/teams/$team/members"),
        Route(HttpMethod.Post, "/api/v1/teams/$team/members/$target"),
        Route(HttpMethod.Delete, "/api/v1/teams/$team/members/$target"),
        // Trainings and events (1.10.0); the event created here goes with the team deleted next.
        Route(HttpMethod.Get, "/api/v1/me/events"),
        Route(HttpMethod.Get, "/api/v1/teams/$team/events"),
        Route(HttpMethod.Post, "/api/v1/teams/$team/events",
            """{"kind":"TRAINING","title":"Revocation matrix","startsAt":"2030-01-01T10:00:00Z","endsAt":"2030-01-01T11:00:00Z"}"""),
        Route(HttpMethod.Put, "/api/v1/events/${UUID(0, 1)}",
            """{"kind":"TRAINING","title":"Revocation matrix","startsAt":"2030-01-01T10:00:00Z","endsAt":"2030-01-01T11:00:00Z","cancelled":false}"""),
        Route(HttpMethod.Delete, "/api/v1/events/${UUID(0, 1)}"),
        // An unknown event is 404 for the holder; answering is covered in TeamEventHttpTest (403 for unknown events).
        Route(HttpMethod.Get, "/api/v1/events/${UUID(0, 1)}/attendance"),
        Route(HttpMethod.Get, "/api/v1/teams/$team/attendance-statistics"),
        // Volunteer shifts (1.16.0): unknown events and shifts are 404 for the club-wide holder. Signing up is covered in
        // EventShiftHttpTest (the holder belongs to no team, so it is 403 there by design).
        Route(HttpMethod.Get, "/api/v1/events/${UUID(0, 1)}/shifts"),
        Route(HttpMethod.Post, "/api/v1/events/${UUID(0, 1)}/shifts",
            """{"title":"Revocation shift","startsAt":"2030-01-01T10:00:00Z","endsAt":"2030-01-01T11:00:00Z","needed":1}"""),
        Route(HttpMethod.Put, "/api/v1/shifts/${UUID(0, 4)}",
            """{"title":"Revocation shift","startsAt":"2030-01-01T10:00:00Z","endsAt":"2030-01-01T11:00:00Z","needed":1}"""),
        Route(HttpMethod.Delete, "/api/v1/shifts/${UUID(0, 4)}"),
        Route(HttpMethod.Delete, "/api/v1/teams/$team"),
        Route(HttpMethod.Post, "/api/v1/members/$target/invitation"),
        Route(HttpMethod.Delete, "/api/v1/members/$target/invitation"),
        Route(HttpMethod.Delete, "/api/v1/members/$target/account-link"),
        Route(HttpMethod.Get, "/api/v1/members/$target/details"),
        // The member list as CSV (1.13.0); the import needs a CSV body and is covered in MemberListHttpTest.
        Route(HttpMethod.Get, "/api/v1/members/export.csv?lang=de"),
        Route(HttpMethod.Get, "/api/v1/members/$target/export"),
        // Guardian editor routes; the target is an adult, so the authorized pass gets 404 (no guardians) and 204.
        Route(HttpMethod.Get, "/api/v1/members/$target/guardians"),
        Route(HttpMethod.Post, "/api/v1/members/$target/guardians", """{"guardianMemberId":"$holder"}"""),
        Route(HttpMethod.Delete, "/api/v1/members/$target/guardians/$holder"),
        // The holder's own profile; without If-Match the authorized change pass stops at 428 and changes nothing.
        Route(HttpMethod.Get, "/api/v1/me/profile"),
        Route(HttpMethod.Put, "/api/v1/me/profile/contacts", """{"email":"holder@example.invalid","phone":"+41000000001"}"""),
        // The public-list switch (1.15.0), also stopping at 428 without If-Match.
        Route(HttpMethod.Put, "/api/v1/me/profile/public-listing", """{"listedPublicly":false}"""),
        // The holder's own calendar feed (1.17.0); the feed itself has no session and is covered in CalendarFeedHttpTest.
        Route(HttpMethod.Get, "/api/v1/me/calendar-feed"),
        // Email notification settings (1.19.0): the holder's own switches.
        Route(HttpMethod.Get, "/api/v1/me/notifications"),
        // Absences (1.20.0); entering and deleting are covered in AbsenceHttpTest.
        Route(HttpMethod.Get, "/api/v1/me/absences"),
        // Join requests (1.22.0): the switch and the list; accepting is covered in JoinRequestHttpTest.
        Route(HttpMethod.Get, "/api/v1/club/join-requests/settings"),
        Route(HttpMethod.Get, "/api/v1/join-requests"),
        Route(HttpMethod.Delete, "/api/v1/join-requests/${UUID(0, 4)}"),
        // Carpooling (1.21.0): unknown events and rides are 404 for the club-wide holder; booking is in RideHttpTest.
        Route(HttpMethod.Get, "/api/v1/events/${UUID(0, 1)}/rides"),
        Route(HttpMethod.Post, "/api/v1/events/${UUID(0, 1)}/rides",
            """{"direction":"THERE","seats":2,"meetingPoint":"Revocation","departsAt":"2030-01-01T10:00:00Z"}"""),
        Route(HttpMethod.Put, "/api/v1/rides/${UUID(0, 4)}",
            """{"direction":"THERE","seats":2,"meetingPoint":"Revocation","departsAt":"2030-01-01T10:00:00Z"}"""),
        Route(HttpMethod.Delete, "/api/v1/rides/${UUID(0, 4)}"),
        Route(HttpMethod.Put, "/api/v1/me/notifications",
            """{"eventChanges":true,"newEvents":true,"announcements":true,"shiftReminders":true,"language":"de"}"""),
        Route(HttpMethod.Post, "/api/v1/me/calendar-feed"),
        Route(HttpMethod.Delete, "/api/v1/me/calendar-feed"),
        Route(HttpMethod.Put, "/api/v1/members/$target/public-listing", """{"listedPublicly":false}"""),
        // Membership fees (1.12.0): unknown periods are 404 for the holder, so nothing else changes.
        Route(HttpMethod.Get, "/api/v1/fee-periods"),
        Route(HttpMethod.Post, "/api/v1/fee-periods", """{"name":"Revocation fees","dueDate":"2030-01-31","amountCents":100}"""),
        Route(HttpMethod.Patch, "/api/v1/fee-periods/${UUID(0, 2)}", """{"name":"Revocation fees","dueDate":"2030-01-31"}"""),
        Route(HttpMethod.Delete, "/api/v1/fee-periods/${UUID(0, 2)}", """{"name":"Revocation fees"}"""),
        Route(HttpMethod.Get, "/api/v1/fee-periods/${UUID(0, 2)}/fees"),
        Route(HttpMethod.Put, "/api/v1/fee-periods/${UUID(0, 2)}/fees/$target", """{"amountCents":100,"paidOn":null}"""),
        Route(HttpMethod.Delete, "/api/v1/fee-periods/${UUID(0, 2)}/fees/$target"),
        Route(HttpMethod.Get, "/api/v1/me/fees"),
        // Announcements (1.14.0): a club-wide post by the holder; unknown announcements are 404.
        Route(HttpMethod.Get, "/api/v1/me/announcements"),
        Route(HttpMethod.Post, "/api/v1/announcements", """{"teamId":null,"title":"Revocation news","body":"Synthetic"}"""),
        Route(HttpMethod.Put, "/api/v1/announcements/${UUID(0, 3)}", """{"title":"Revocation news","body":"Synthetic"}"""),
        Route(HttpMethod.Delete, "/api/v1/announcements/${UUID(0, 3)}"),
        Route(HttpMethod.Post, "/api/v1/members/$erasable/erase", """{"confirmation":"Erasable Person"}"""),
        // Last: an archived target is hidden from the role and grant routes by design.
        Route(HttpMethod.Post, "/api/v1/members/$target/archive"),
        // After the archive, so the authorized pass really reactivates the target (204).
        Route(HttpMethod.Post, "/api/v1/members/$target/reactivate"),
    )

    private suspend fun ApplicationTestBuilder.send(route: Route) = client.request(route.path) {
        method = route.method
        header(HttpHeaders.Cookie, "${cookies.sessionName}=holder")
        if (route.method != HttpMethod.Get) {
            header(HttpHeaders.Origin, origin)
            header("X-CSRF-Token", csrf)
        }
        if (route.body != null) {
            header(HttpHeaders.ContentType, "application/json")
            setBody(route.body)
        }
    }

    private fun app(db: DatabaseRuntime, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = db) }
        block()
    }

    /** Denial, not absence: while the identity and grants exist, no route may answer 401/403/5xx. */
    @Test
    @Order(1)
    fun theHolderIsAuthorizedOnEveryRouteBeforeRevocation() = app(requireNotNull(database)) {
        val failures = mutableListOf<String>()
        for (route in routes()) {
            val status = send(route).status.value
            // 404 is acceptable only for a target another step already removed (the team delete is last).
            if (status in setOf(401, 403, 503) || status >= 500) {
                failures += "${route.method} ${route.path}: unexpected $status before revocation"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    @Test
    @Order(2)
    fun aValidSessionWithARevokedIdentityIsDeniedOnEveryRoute() {
        val db = requireNotNull(database)
        runBlocking {
            db.onDatabaseThread { tx ->
                // Undo what the authorized pass did, so every denial below has a real target.
                tx.execute("DELETE FROM members WHERE first_name = 'Denied' AND last_name = 'Person'")
                tx.execute("UPDATE members SET archived_at = NULL, role = 'MEMBER' WHERE id = ?", target)
                tx.execute("DELETE FROM club_capability_grants WHERE member_id = ?", target)
                tx.execute("DELETE FROM member_team_assignments WHERE member_id = ?", target)
                tx.execute("DELETE FROM teams WHERE id <> ? AND name LIKE 'Revocation%'", team)
                tx.execute("INSERT INTO teams (id, name) VALUES (?, ?) ON CONFLICT DO NOTHING", team,
                    "Revocation team again")
                insertErasable(tx)
                tx.execute("DELETE FROM account_identities WHERE account_id = ?", holderAccount)
            }
        }
        app(db) {
            val failures = mutableListOf<String>()
            for (route in routes()) {
                val response = send(route)
                val text = response.bodyAsText()
                val name = "${route.method} ${route.path}"
                if (response.status != HttpStatusCode.Forbidden) {
                    failures += "$name: expected 403 but was ${response.status}"
                }
                if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$name: missing no-store"
                if (text.contains("person@example.invalid") || text.contains("Synthetic")) {
                    failures += "$name: leaked member data"
                }
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
        }
        // Nothing the revoked session attempted may have changed data.
        runBlocking {
            db.onDatabaseThread { tx ->
                assertEquals(1L, tx.fetchValue("SELECT COUNT(*) FROM members WHERE id = ? AND archived_at IS NULL " +
                    "AND role = 'MEMBER'", target)?.toString()?.toLong(), "target member changed")
                assertEquals(0L, tx.fetchValue("SELECT COUNT(*) FROM club_capability_grants WHERE member_id = ?",
                    target)?.toString()?.toLong(), "grant written")
                assertEquals(0L, tx.fetchValue("SELECT COUNT(*) FROM members WHERE first_name = 'Denied'")
                    ?.toString()?.toLong(), "member created")
                assertEquals(1L, tx.fetchValue("SELECT COUNT(*) FROM members WHERE id = ?", erasable)
                    ?.toString()?.toLong(), "member erased")
            }
        }
    }
}
