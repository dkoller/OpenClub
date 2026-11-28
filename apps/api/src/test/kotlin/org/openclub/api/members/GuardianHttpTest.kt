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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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

/** Guardian routes (OpenAPI 1.6.0): editors manage guardianships, guardians reach only their own minors. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuardianHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val members = mutableListOf<UUID>()
    private val handles = listOf("editor", "g1", "g2", "g3", "g4", "plain")
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }
    private fun count(query: String, vararg args: Any): Int = sql { (it.fetchValue(query, *args) as Number).toInt() }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { tx ->
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Guardian HTTP Club', ?)", club, "guardian-http-$club")
            for (handle in handles) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "guardian-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    members += it
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                           VALUES (?, ?, ?, 'Adult', DATE '1980-01-01', 'a@example.invalid', '+41000000000', ?)""",
                        it, account, handle, if (handle == "editor") "ADMIN" else "MEMBER")
                }
            }
            tx.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", memberOf.getValue("editor"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", members.toTypedArray())
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", members.toTypedArray())
                tx.execute("DELETE FROM teams WHERE name LIKE 'Guardian test %'")
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun minor(first: String = "Kid", born: String = "2015-03-04"): UUID = UUID.randomUUID().also { id ->
        members += id
        sql { it.execute("INSERT INTO members (id, first_name, last_name, date_of_birth, is_player) VALUES (?, ?, 'Minor', ?::date, TRUE)", id, first, born) }
    }

    private fun link(minor: UUID, guardian: String) = sql {
        it.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", minor, memberOf.getValue(guardian))
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { checkContract(); module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod, path: String, handle: String?, body: String? = null,
        withOrigin: String? = origin, withCsrf: String? = csrf,
    ): HttpResponse = client.request(path) {
        this.method = method
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        if (method != HttpMethod.Get) {
            if (withOrigin != null) header(HttpHeaders.Origin, withOrigin)
            if (withCsrf != null) header("X-CSRF-Token", withCsrf)
        }
        if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun guardians(minor: Any) = "/api/v1/members/$minor/guardians"
    private fun body(guardian: String) = """{"guardianMemberId":"${memberOf.getValue(guardian)}"}"""
    private suspend fun ApplicationTestBuilder.add(minor: Any, guardian: String, actor: String = "editor") =
        send(HttpMethod.Post, guardians(minor), actor, body(guardian))

    private class Check { val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
        }
        fun done() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    @Test
    fun editorRoutesAnswerSessionThenOriginAndCsrfThenCapabilityThenValidation() = app {
        val kid = minor()
        val check = Check()
        check.expect("list no session", send(HttpMethod.Get, guardians(kid), null), HttpStatusCode.Unauthorized)
        check.expect("add no session", add(kid, "g1", actor = "ghost"), HttpStatusCode.Unauthorized)
        for ((label, o, c) in listOf(Triple("no Origin", null, csrf), Triple("wrong Origin", "https://evil.example", csrf),
            Triple("no CSRF", origin, null), Triple("wrong CSRF", origin, "y".repeat(43)))) {
            check.expect("add $label", send(HttpMethod.Post, guardians(kid), "editor", body("g1"), o, c), HttpStatusCode.Forbidden)
            check.expect("remove $label", send(HttpMethod.Delete, "${guardians(kid)}/${memberOf["g1"]}", "editor", null, o, c),
                HttpStatusCode.Forbidden)
        }
        // Without EDIT_MEMBERS (a guardian included): 403 for any ID and body, never 400 or 404.
        for (handle in listOf("plain", "g1")) {
            for (id in listOf(kid, UUID.randomUUID(), "not-a-uuid")) {
                check.expect("$handle list $id", send(HttpMethod.Get, guardians(id), handle), HttpStatusCode.Forbidden)
                check.expect("$handle add $id", send(HttpMethod.Post, guardians(id), handle, "{garbage"), HttpStatusCode.Forbidden)
                check.expect("$handle remove $id", send(HttpMethod.Delete, "${guardians(id)}/x", handle), HttpStatusCode.Forbidden)
            }
        }
        check.expect("list bad id", send(HttpMethod.Get, guardians("not-a-uuid"), "editor"), HttpStatusCode.BadRequest)
        check.expect("remove bad ids", send(HttpMethod.Delete, "${guardians(kid)}/not-a-uuid", "editor"), HttpStatusCode.BadRequest)
        for (bad in listOf("", "{}", "[]", """{"guardianMemberId":"x"}""", """{"guardianMemberId":1}""",
            """{"guardianMemberId":"${memberOf["g1"]}","role":"ADMIN"}""")) {
            check.expect("add body $bad", send(HttpMethod.Post, guardians(kid), "editor", bad), HttpStatusCode.BadRequest)
        }
        check.done()
        assertEquals(0, count("SELECT COUNT(*) FROM member_guardianships WHERE minor_member_id = ?", kid))
    }

    @Test
    fun editorsAddListAndRemoveGuardiansWithinTheRulesAndEveryChangeIsAudited() = app {
        val kid = minor()
        assertEquals(HttpStatusCode.NoContent, add(kid, "g1").status)
        assertEquals(1, count("""SELECT COUNT(*) FROM audit_events WHERE event_type = 'GUARDIAN_LINKED' AND target_member_id = ?
            AND related_member_id = ? AND actor_account_id = ?""", kid, memberOf.getValue("g1"), accounts.getValue("editor")))
        val listed = send(HttpMethod.Get, guardians(kid), "editor")
        assertEquals("no-store", listed.headers[HttpHeaders.CacheControl])
        val items = listed.json()["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(memberOf.getValue("g1").toString()), items.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(setOf("id", "firstName", "lastName"), items.single().keys)

        assertEquals(HttpStatusCode.Conflict, add(kid, "g1").status, "already a guardian")
        val unlinkedAdult = UUID.randomUUID().also { members += it }
        sql { it.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone)
            VALUES (?, 'Unlinked', 'Adult', DATE '1980-01-01', 'u@example.invalid', '+41000000001')""", unlinkedAdult) }
        val otherKid = minor("Other")
        for ((label, guardian) in listOf("unlinked adult" to unlinkedAdult, "a minor" to otherKid, "the minor itself" to kid)) {
            assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, guardians(kid), "editor",
                """{"guardianMemberId":"$guardian"}""").status, label)
        }
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, guardians(kid), "editor",
            """{"guardianMemberId":"${UUID.randomUUID()}"}""").status, "unknown guardian")
        assertEquals(HttpStatusCode.NotFound, add(memberOf.getValue("plain"), "g1").status, "an adult has no guardians")
        assertEquals(HttpStatusCode.NotFound, add(UUID.randomUUID(), "g1").status, "unknown minor")
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, guardians(memberOf.getValue("plain")), "editor").status)

        assertEquals(HttpStatusCode.NoContent, add(kid, "g2").status)
        assertEquals(HttpStatusCode.Conflict, add(kid, "g3").status, "at most two guardians")
        assertEquals(0, count("SELECT COUNT(*) FROM club_capability_grants WHERE member_id IN (?, ?)",
            memberOf.getValue("g1"), memberOf.getValue("g2")), "being a guardian grants no capability")

        val remove = "${guardians(kid)}/${memberOf["g2"]}"
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, remove, "editor").status)
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'GUARDIAN_UNLINKED' AND target_member_id = ? AND related_member_id = ?",
            kid, memberOf.getValue("g2")))
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, remove, "editor").status, "idempotent")
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'GUARDIAN_UNLINKED' AND target_member_id = ?", kid),
            "an absent guardianship writes no event")
        assertEquals(1, send(HttpMethod.Get, guardians(kid), "editor").json()["items"]!!.jsonArray.size)
    }

    @Test
    fun editorsSeeMinorsWithoutAGuardianAndNobodyElseDoes() = app {
        val lonely = minor("Lonely")
        val cared = minor("Cared").also { link(it, "g1") }
        val archived = minor("Archived").also { id -> sql { it.execute("UPDATE members SET archived_at = now() WHERE id = ?", id) } }
        val missing = "/api/v1/guardians/missing"
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, missing, null).status)
        val refused = send(HttpMethod.Get, missing, "plain")
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("no-store", refused.headers[HttpHeaders.CacheControl])
        val listed = send(HttpMethod.Get, missing, "editor")
        assertEquals("no-store", listed.headers[HttpHeaders.CacheControl])
        val items = listed.json()["items"]!!.jsonArray.map { it.jsonObject }
        val ids = items.map { it["id"]!!.jsonPrimitive.content }
        assertTrue(lonely.toString() in ids, "a minor without a guardian is listed")
        assertFalse(cared.toString() in ids, "a minor with a guardian is not")
        assertFalse(archived.toString() in ids, "an archived minor is not")
        assertFalse(memberOf.getValue("plain").toString() in ids, "an adult is not")
        assertEquals(setOf("id", "firstName", "lastName"), items.first { it["id"]!!.jsonPrimitive.content == lonely.toString() }.keys)
    }

    @Test
    fun aGuardianSeesOnlyTheirOwnMinorsAndMayReplaceOnlyTheirContacts() = app {
        val kid = minor("Mine")
        val notMine = minor("NotMine")
        link(kid, "g3")
        val team = UUID.randomUUID()
        sql {
            it.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Guardian test ${team.toString().take(8)}")
            it.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, kid)
        }
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/v1/me/dependents", null).status)
        val mine = send(HttpMethod.Get, "/api/v1/me/dependents", "g3")
        assertEquals("no-store", mine.headers[HttpHeaders.CacheControl])
        assertEquals(listOf(kid.toString()), mine.json()["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(0, send(HttpMethod.Get, "/api/v1/me/dependents", "plain").json()["items"]!!.jsonArray.size,
            "anyone else gets an empty list, not 403")
        assertEquals(0, send(HttpMethod.Get, "/api/v1/me/dependents", "editor").json()["items"]!!.jsonArray.size,
            "editing rights do not make anyone a guardian")

        val read = send(HttpMethod.Get, "/api/v1/me/dependents/$kid", "g3")
        assertEquals(HttpStatusCode.OK, read.status)
        val details = read.json()
        assertEquals(setOf("id", "firstName", "lastName", "dateOfBirth", "email", "phone", "isPlayer", "teams"), details.keys)
        assertEquals("2015-03-04", details["dateOfBirth"]!!.jsonPrimitive.content)
        assertEquals(listOf("Guardian test ${team.toString().take(8)}"), details["teams"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'DEPENDENT_READ' AND target_member_id = ? AND related_member_id = ?",
            kid, memberOf.getValue("g3")))

        // Every refusal looks the same and is never audited.
        val reference = send(HttpMethod.Get, "/api/v1/me/dependents/$notMine", "g3")
        assertEquals(HttpStatusCode.Forbidden, reference.status)
        for ((handle, path) in listOf("g3" to UUID.randomUUID().toString(), "g3" to "not-a-uuid", "plain" to kid.toString(),
            "editor" to kid.toString())) {
            val refused = send(HttpMethod.Get, "/api/v1/me/dependents/$path", handle)
            assertEquals(HttpStatusCode.Forbidden, refused.status, "$handle $path")
            assertEquals(reference.bodyAsText(), refused.bodyAsText(), "$handle $path")
        }
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'DEPENDENT_READ' AND target_member_id = ?", notMine))

        val contacts = "/api/v1/me/dependents/$kid/contacts"
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, contacts, "plain", "{garbage").status, "relationship before body")
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, "/api/v1/me/dependents/$notMine/contacts", "g3", "{}").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, contacts, "g3", "{}", withCsrf = null).status)
        for (bad in listOf("{garbage", """{"email":"no-at-sign"}""", """{"phone":1}""", """{"email":"k@example.invalid","isPlayer":false}""")) {
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, contacts, "g3", bad).status, bad)
        }
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, contacts, "g3",
            """{"email":"kid@example.invalid","phone":"+41000000077"}""").status)
        assertEquals(listOf<Any?>("kid@example.invalid", "+41000000077", "Mine", true),
            sql { it.fetchOne("SELECT email, phone, first_name, is_player FROM members WHERE id = ?", kid)!!.let { r -> (0..3).map(r::get) } })
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, contacts, "g3", "{}").status, "omitted fields are cleared")
        assertNull(sql { it.fetchValue("SELECT email FROM members WHERE id = ?", kid) })
        assertEquals(2, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'DEPENDENT_UPDATED' AND target_member_id = ?", kid))
    }

    @Test
    fun aCorrectedBirthDateAChangedAdultAgeOrARebindNeverReviveAGuardianship() = app {
        // A birth date that makes the minor an adult ends the guardianship; correcting it back does not revive it.
        val corrected = minor("Corrected")
        link(corrected, "g2")
        sql { it.execute("UPDATE members SET date_of_birth = DATE '1990-01-01', email = 'c@example.invalid', phone = '+41000000003' WHERE id = ?", corrected) }
        sql { it.execute("UPDATE members SET date_of_birth = DATE '2015-03-04' WHERE id = ?", corrected) }
        assertEquals(0, count("SELECT COUNT(*) FROM member_guardianships WHERE minor_member_id = ?", corrected))
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/me/dependents/$corrected", "g2").status)

        // A 19-year-old guardian is a minor under a raised adult age: the guardianship ends for good.
        val youngAccount = UUID.randomUUID().also { accounts["young"] = it }
        val young = UUID.randomUUID().also { members += it; memberOf["young"] = it }
        sql { tx ->
            tx.execute("INSERT INTO accounts (id) VALUES (?)", youngAccount)
            tx.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                          VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), youngAccount, "guardian-$youngAccount")
            tx.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                          VALUES (?, ?, 'Young', 'Adult', CURRENT_DATE - INTERVAL '19 years', 'y@example.invalid', '+41000000004')""",
                young, youngAccount)
        }
        val sibling = minor("Sibling")
        link(sibling, "young")
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/v1/me/dependents/$sibling", "young").status)
        sql { it.execute("UPDATE clubs SET adult_age_years = 21 WHERE id = ?", club) }
        try {
            assertEquals(0, count("SELECT COUNT(*) FROM member_guardianships WHERE minor_member_id = ?", sibling))
        } finally {
            sql { it.execute("UPDATE clubs SET adult_age_years = 18 WHERE id = ?", club) }
        }
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/me/dependents/$sibling", "young").status)

        // An administrator recovery binds the record to another account: that person inherits no guardianship.
        val rebound = minor("Rebound")
        link(rebound, "young")
        val other = UUID.randomUUID().also { accounts["young-other"] = it }
        sql {
            it.execute("INSERT INTO accounts (id) VALUES (?)", other)
            it.execute("UPDATE members SET account_id = ? WHERE id = ?", other, young)
        }
        assertEquals(0, count("SELECT COUNT(*) FROM member_guardianships WHERE minor_member_id = ?", rebound))
    }

    @Test
    fun accessEndsAtTheAdultAgeOnArchiveOnUnlinkAndWithARevokedIdentity() = app {
        // Adulthood: the row may remain, but every read and write checks the age.
        val growing = minor("Growing")
        link(growing, "g1")
        sql { it.execute("UPDATE members SET date_of_birth = DATE '1990-01-01', email = 'g@example.invalid', phone = '+41000000002' WHERE id = ?", growing) }
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/me/dependents/$growing", "g1").status)
        // g1 may be a guardian in other tests of this class, so check this member is gone, not that the list is empty.
        assertFalse(send(HttpMethod.Get, "/api/v1/me/dependents", "g1").json()["items"]!!.jsonArray
            .any { it.jsonObject["id"]!!.jsonPrimitive.content == growing.toString() }, "an adult is no longer a dependent")

        // Archive: the guardianship ends with the minor.
        val archived = minor("Archived")
        link(archived, "g2")
        sql { it.execute("UPDATE members SET archived_at = now() WHERE id = ?", archived) }
        assertEquals(0, count("SELECT COUNT(*) FROM member_guardianships WHERE minor_member_id = ?", archived))
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/me/dependents/$archived", "g2").status)

        // A revoked identity: the session alone is not enough.
        val kid = minor("Revoked")
        link(kid, "g4")
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/v1/me/dependents/$kid", "g4").status)
        sql { it.execute("DELETE FROM account_identities WHERE account_id = ?", accounts.getValue("g4")) }
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/me/dependents/$kid", "g4").status)
        assertEquals(0, send(HttpMethod.Get, "/api/v1/me/dependents", "g4").json()["items"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, "/api/v1/me/dependents/$kid/contacts", "g4", "{}").status)

        // Unlink: the guardian's login goes, and with it the guardianship.
        sql { it.execute("UPDATE members SET account_id = NULL WHERE id = ?", memberOf.getValue("g4")) }
        assertEquals(0, count("SELECT COUNT(*) FROM member_guardianships WHERE guardian_member_id = ?", memberOf.getValue("g4")))
    }
}
