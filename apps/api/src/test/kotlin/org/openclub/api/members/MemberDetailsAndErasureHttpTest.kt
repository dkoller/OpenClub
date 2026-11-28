package org.openclub.api.members

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
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
 * Member detail read and erasure routes (OpenAPI 1.3.0): order of answers, which fields each caller sees,
 * team scoping and its audit trail, archived reads by editors, and the single generic erasure refusal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberDetailsAndErasureHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val members = mutableListOf<UUID>()
    private val teams = mutableListOf<UUID>()
    private val handles = listOf("editor", "teamManager", "clubManager", "viewer", "directory", "plain")
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }
    private lateinit var ownTeam: UUID
    private lateinit var otherTeam: UUID

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { tx ->
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Details HTTP Club', ?)", club, "details-http-$club")
            val linked = mutableMapOf<String, UUID>()
            for (handle in handles) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "details-$account")
                linked[handle] = UUID.randomUUID().also {
                    members += it
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                           VALUES (?, ?, 'Http', 'Actor', DATE '1985-01-01', 'a@example.invalid', '+41000000000', ?)""",
                        it, account, if (handle == "editor") "ADMIN" else "MEMBER")
                }
            }
            ownTeam = UUID.randomUUID().also { teams += it }
            otherTeam = UUID.randomUUID().also { teams += it }
            tx.execute("INSERT INTO teams (id, name) VALUES (?, ?)", ownTeam, "Details own $ownTeam")
            tx.execute("INSERT INTO teams (id, name) VALUES (?, ?)", otherTeam, "Details other $otherTeam")
            tx.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", linked.getValue("editor"))
            tx.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_TEAMS')", linked.getValue("clubManager"))
            tx.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", linked.getValue("directory"))
            tx.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'MANAGE_TEAMS')",
                ownTeam, linked.getValue("teamManager"))
            tx.execute("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES (?, ?, 'VIEW_TEAMS')",
                ownTeam, linked.getValue("viewer"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", members.toTypedArray())
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", members.toTypedArray())
                tx.execute("DELETE FROM teams WHERE id = ANY(?::uuid[])", teams.toTypedArray())
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    /** An unlinked adult with every field set; optionally archived and on [team]. */
    private fun member(first: String = "Detail", last: String = "Person", archived: Boolean = false, team: UUID? = null): UUID =
        UUID.randomUUID().also { id ->
            members += id
            sql { tx ->
                tx.execute(
                    """INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone, gender, archived_at)
                       VALUES (?, ?, ?, DATE '1990-02-03', 'd@example.invalid', '+41000000003', 'synthetic',
                               CASE WHEN ? THEN now() END)""", id, first, last, archived)
                if (team != null) tx.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, id)
            }
        }

    private fun contactReads(member: UUID, actor: String): Int = sql {
        (it.fetchValue("""SELECT COUNT(*) FROM audit_events WHERE event_type = 'CONTACT_READ'
            AND target_member_id = ? AND actor_account_id = ?""", member, accounts.getValue(actor)) as Number).toInt()
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { checkContract(); module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod, path: String, handle: String?, body: String? = null,
        contentType: String? = "application/json", withOrigin: String? = origin, withCsrf: String? = csrf,
    ): HttpResponse = client.request(path) {
        this.method = method
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        if (withOrigin != null) header(HttpHeaders.Origin, withOrigin)
        if (withCsrf != null) header("X-CSRF-Token", withCsrf)
        if (body != null) { if (contentType != null) header(HttpHeaders.ContentType, contentType); setBody(body) }
    }

    private suspend fun ApplicationTestBuilder.details(id: Any, handle: String?) =
        send(HttpMethod.Get, "/api/v1/members/$id/details", handle, withOrigin = null, withCsrf = null)

    private suspend fun ApplicationTestBuilder.erase(id: Any, handle: String?, confirmation: String?, raw: String? = null,
                                                     withOrigin: String? = origin, withCsrf: String? = csrf) =
        send(HttpMethod.Post, "/api/v1/members/$id/erase", handle,
            raw ?: confirmation?.let { """{"confirmation":${JsonPrimitive(it)}}""" }, withOrigin = withOrigin, withCsrf = withCsrf)

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private class Check { val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
        }
        fun done() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    @Test
    fun detailsAnswerSessionThenAccessThenValidation() = app {
        val target = member(team = ownTeam)
        val check = Check()
        check.expect("no session", details(target, null), HttpStatusCode.Unauthorized)
        check.expect("unknown session", details(target, "ghost"), HttpStatusCode.Unauthorized)
        // Neither directory viewers, team viewers nor plain members may read details, whatever ID they send.
        for (handle in listOf("plain", "directory", "viewer")) {
            for (id in listOf(target, UUID.randomUUID(), "not-a-uuid")) {
                val response = details(id, handle)
                check.expect("$handle $id", response, HttpStatusCode.Forbidden)
                if (response.bodyAsText() != """{"code":"forbidden"}""") check.failures += "$handle $id: body ${response.bodyAsText()}"
            }
        }
        // A caller who may read some details gets 400 for a malformed ID.
        for (handle in listOf("editor", "teamManager", "clubManager")) {
            for (id in listOf("not-a-uuid", "123", "A".repeat(36))) check.expect("$handle $id", details(id, handle), HttpStatusCode.BadRequest)
        }
        // A read needs neither Origin nor CSRF, and a foreign Origin does not change the answer.
        check.expect("editor with foreign Origin",
            send(HttpMethod.Get, "/api/v1/members/$target/details", "editor", withOrigin = "https://evil.example"), HttpStatusCode.OK)
        check.done()
        assertEquals(0, contactReads(target, "teamManager"), "refusals and editor reads write no CONTACT_READ")
    }

    @Test
    fun anEditorReadsEveryFieldIncludingOfAnArchivedMemberWithoutAuditingAContactRead() = app {
        val active = member(first = "Edit", last = "Visible", team = ownTeam)
        val response = details(active, "editor")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = response.json()
        assertEquals(setOf("id", "firstName", "lastName", "dateOfBirth", "email", "phone", "gender", "isPlayer",
            "archivedAt", "hasLinkedLogin", "listedPublicly", "jsPersonNumber", "invitation"), body.keys)
        assertEquals(active.toString(), body["id"]!!.jsonPrimitive.content)
        assertEquals("Edit", body["firstName"]!!.jsonPrimitive.content)
        assertEquals("1990-02-03", body["dateOfBirth"]!!.jsonPrimitive.content)
        assertEquals("d@example.invalid", body["email"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["archivedAt"])
        assertFalse(body["hasLinkedLogin"]!!.jsonPrimitive.boolean)
        assertEquals("""{"status":"NONE"}""", body["invitation"].toString())

        // An open invitation is visible as status and expiry, never the secret.
        val created = send(HttpMethod.Post, "/api/v1/members/$active/invitation", "editor")
        assertEquals(HttpStatusCode.Created, created.status)
        val secret = created.json()["secret"]!!.jsonPrimitive.content
        val invited = details(active, "editor")
        val invitation = invited.json()["invitation"]!!.jsonObject
        assertEquals("OPEN", invitation["status"]!!.jsonPrimitive.content)
        assertNotNull(invitation["expiresAt"])
        assertFalse(invited.bodyAsText().contains(secret), "the invitation secret is never returned")

        // The editor's own linked record reports a linked login, never the account.
        val self = sql { it.fetchValue("SELECT id FROM members WHERE account_id = ?", accounts.getValue("editor")) } as UUID
        val selfText = details(self, "editor").bodyAsText()
        assertTrue(Json.parseToJsonElement(selfText).jsonObject["hasLinkedLogin"]!!.jsonPrimitive.boolean)
        assertFalse(selfText.contains(accounts.getValue("editor").toString()), "account ID never exposed")

        val archived = member(first = "Edit", last = "Archived", archived = true)
        val archivedBody = details(archived, "editor").json()
        assertNotEquals(JsonNull, archivedBody["archivedAt"], "archived members stay readable for editors")
        assertEquals("Archived", archivedBody["lastName"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.NotFound, details(UUID.randomUUID(), "editor").status, "unknown member is 404 for editors only")
        assertEquals(0, sql { (it.fetchValue("SELECT COUNT(*) FROM audit_events WHERE event_type = 'CONTACT_READ' " +
            "AND actor_account_id = ?", accounts.getValue("editor")) as Number).toInt() })
    }

    @Test
    fun aTeamManagerReadsOnlyContactsOfActiveMembersOfTheirTeamsAndEachReadIsAudited() = app {
        val own = member(first = "Own", last = "Player", team = ownTeam)
        val other = member(first = "Other", last = "Player", team = otherTeam)
        val unassigned = member(first = "No", last = "Team")
        val archivedOwn = member(first = "Gone", last = "Player", team = null, archived = true)

        val response = details(own, "teamManager")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = response.json()
        assertEquals(setOf("id", "firstName", "lastName", "email", "phone", "isPlayer"), body.keys,
            "no birth date, gender, archive state, login or invitation for team managers")
        assertEquals("+41000000003", body["phone"]!!.jsonPrimitive.content)
        assertEquals(1, contactReads(own, "teamManager"))
        assertEquals(HttpStatusCode.OK, details(own, "teamManager").status)
        assertEquals(2, contactReads(own, "teamManager"), "every read is audited")

        // Outside their teams, archived or unknown: the same 403, and nothing audited.
        val reference = details(other, "teamManager")
        assertEquals(HttpStatusCode.Forbidden, reference.status)
        for ((label, id) in listOf("unassigned" to unassigned, "archived" to archivedOwn, "unknown" to UUID.randomUUID())) {
            val refused = details(id, "teamManager")
            assertEquals(HttpStatusCode.Forbidden, refused.status, label)
            assertEquals(reference.bodyAsText(), refused.bodyAsText(), label)
        }
        assertEquals(0, contactReads(other, "teamManager"))
        assertEquals(0, contactReads(unassigned, "teamManager"))

        // A club-wide manager reads members of any team, but still not unassigned or archived ones.
        assertEquals(HttpStatusCode.OK, details(other, "clubManager").status)
        assertEquals(1, contactReads(other, "clubManager"))
        assertEquals(HttpStatusCode.Forbidden, details(unassigned, "clubManager").status)
        assertEquals(HttpStatusCode.Forbidden, details(archivedOwn, "clubManager").status)

        // Removing the member from the team ends access at once.
        sql { it.execute("DELETE FROM member_team_assignments WHERE member_id = ?", own) }
        assertEquals(HttpStatusCode.Forbidden, details(own, "teamManager").status)
        assertEquals(2, contactReads(own, "teamManager"))
    }

    @Test
    fun erasureAnswersSessionThenOriginAndCsrfThenCapabilityThenValidation() = app {
        val target = member(first = "Order", last = "Erasable", archived = true)
        val name = "Order Erasable"
        val check = Check()
        check.expect("no session", erase(target, null, name), HttpStatusCode.Unauthorized)
        check.expect("unknown session", erase(target, "ghost", name), HttpStatusCode.Unauthorized)
        check.expect("no Origin", erase(target, "editor", name, withOrigin = null), HttpStatusCode.Forbidden)
        check.expect("wrong Origin", erase(target, "editor", name, withOrigin = "https://evil.example"), HttpStatusCode.Forbidden)
        check.expect("no CSRF", erase(target, "editor", name, withCsrf = null), HttpStatusCode.Forbidden)
        check.expect("wrong CSRF", erase(target, "editor", name, withCsrf = "y".repeat(43)), HttpStatusCode.Forbidden)
        // Without EDIT_MEMBERS the answer is 403 for any ID and any body, never 400.
        for (handle in listOf("plain", "directory", "teamManager", "clubManager")) {
            for (id in listOf(target, UUID.randomUUID(), "not-a-uuid")) {
                for (raw in listOf("""{"confirmation":"$name"}""", "{garbage")) {
                    check.expect("$handle $id $raw", erase(id, handle, null, raw = raw), HttpStatusCode.Forbidden)
                }
            }
        }
        // With it, malformed input is 400.
        for (id in listOf("not-a-uuid", "123")) check.expect("bad id $id", erase(id, "editor", name), HttpStatusCode.BadRequest)
        for ((label, raw) in listOf("empty" to "", "truncated" to "{\"confirmation\":", "array" to "[]", "null" to "null",
            "missing" to "{}", "numeric" to """{"confirmation":1}""", "null value" to """{"confirmation":null}""",
            "unknown key" to """{"confirmation":"$name","force":true}""", "blank" to """{"confirmation":"   "}""",
            "too long" to """{"confirmation":"${"a".repeat(403)}"}""")) {
            check.expect(label, erase(target, "editor", null, raw = raw), HttpStatusCode.BadRequest)
        }
        check.expect("text/plain", send(HttpMethod.Post, "/api/v1/members/$target/erase", "editor",
            """{"confirmation":"$name"}""", contentType = "text/plain"), HttpStatusCode.BadRequest)
        check.done()
        assertEquals(1, sql { (it.fetchValue("SELECT COUNT(*) FROM members WHERE id = ?", target) as Number).toInt() },
            "no refusal erased anything")
    }

    @Test
    fun anEditorErasesAnArchivedMemberAndEveryOtherRefusalIsTheSame403() = app {
        val archived = member(first = "Erase", last = "Me", archived = true)
        val active = member(first = "Still", last = "Active")
        val self = sql { it.fetchValue("SELECT id FROM members WHERE account_id = ?", accounts.getValue("editor")) } as UUID

        val reference = erase(archived, "editor", "Erase Wrong")
        assertEquals(HttpStatusCode.Forbidden, reference.status, "wrong typed name")
        assertEquals("""{"code":"forbidden"}""", reference.bodyAsText())
        for ((label, id, typed) in listOf(
            Triple("an active member", active, "Still Active"),
            Triple("the editor's own linked administrator record", self, "Http Actor"),
            Triple("an unknown member", UUID.randomUUID(), "Erase Me"),
            Triple("a case-changed name", archived, "erase me"))) {
            val refused = erase(id, "editor", typed)
            assertEquals(HttpStatusCode.Forbidden, refused.status, label)
            assertEquals(reference.bodyAsText(), refused.bodyAsText(), label)
            assertEquals("no-store", refused.headers[HttpHeaders.CacheControl], label)
        }
        assertEquals(2, sql { (it.fetchValue("SELECT COUNT(*) FROM members WHERE id IN (?, ?)", archived, active) as Number).toInt() })

        val erased = erase(archived, "editor", "  Erase Me ")
        assertEquals(HttpStatusCode.NoContent, erased.status, "surrounding whitespace is ignored, as in the service")
        assertEquals("", erased.bodyAsText())
        assertEquals(0, sql { (it.fetchValue("SELECT COUNT(*) FROM members WHERE id = ?", archived) as Number).toInt() })
        assertEquals(1, sql { (it.fetchValue("""SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_ERASED'
            AND target_member_id = ? AND actor_account_id = ?""", archived, accounts.getValue("editor")) as Number).toInt() })
        assertEquals(HttpStatusCode.NotFound, details(archived, "editor").status, "gone for good")
        val again = erase(archived, "editor", "Erase Me")
        assertEquals(HttpStatusCode.Forbidden, again.status, "a second erasure is the generic refusal")
        assertEquals(reference.bodyAsText(), again.bodyAsText())
    }
}
