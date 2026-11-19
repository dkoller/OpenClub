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

/**
 * Full member replacement and reactivation (OpenAPI 1.4.0): order of answers, input matrix, database rules
 * as 400, archived and unknown targets, what is never changed (role, link, grants, teams) and the audit trail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberReplaceAndReactivateHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val members = mutableListOf<UUID>()
    private val handles = listOf("editor", "directory", "plain")
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
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Replace HTTP Club', ?)", club, "replace-http-$club")
            for (handle in handles) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "replace-$account")
                val member = UUID.randomUUID().also { members += it }
                tx.execute(
                    """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                       VALUES (?, ?, 'Http', 'Actor', DATE '1985-01-01', 'a@example.invalid', '+41000000000', ?)""",
                    member, account, if (handle == "editor") "ADMIN" else "MEMBER")
                when (handle) {
                    "editor" -> tx.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", member)
                    "directory" -> tx.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", member)
                }
            }
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", members.toTypedArray())
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", members.toTypedArray())
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun member(birth: String = "1990-02-03", email: String? = "m@example.invalid", phone: String? = "+41000000005",
                       archived: Boolean = false): UUID = UUID.randomUUID().also { id ->
        members += id
        sql { it.execute(
            """INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone, gender, archived_at)
               VALUES (?, 'Edit', 'Target', ?::date, ?, ?, 'synthetic', CASE WHEN ? THEN now() END)""",
            id, birth, email, phone, archived) }
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod, path: String, handle: String?, body: String? = null,
        contentType: String? = "application/json", withOrigin: String? = origin, withCsrf: String? = csrf,
        ifMatch: String? = null,
    ): HttpResponse = client.request(path) {
        this.method = method
        if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        if (withOrigin != null) header(HttpHeaders.Origin, withOrigin)
        if (withCsrf != null) header("X-CSRF-Token", withCsrf)
        if (body != null) { if (contentType != null) header(HttpHeaders.ContentType, contentType); setBody(body) }
    }

    /** The member's current version as its ETag; an unknown or malformed ID gets a well-formed placeholder. */
    private fun currentETag(id: Any): String = "\"" + ((runCatching { UUID.fromString(id.toString()) }.getOrNull()
        ?.let { sql { db -> db.fetchValue("SELECT row_version FROM members WHERE id = ?", it) } } as Number?)?.toLong() ?: 1L) + "\""

    private suspend fun ApplicationTestBuilder.replace(id: Any, handle: String?, body: String?, withOrigin: String? = origin,
                                                       withCsrf: String? = csrf, ifMatch: String? = currentETag(id)) =
        send(HttpMethod.Put, "/api/v1/members/$id", handle, body, withOrigin = withOrigin, withCsrf = withCsrf, ifMatch = ifMatch)

    private suspend fun ApplicationTestBuilder.reactivate(id: Any, handle: String?, withOrigin: String? = origin,
                                                          withCsrf: String? = csrf) =
        send(HttpMethod.Post, "/api/v1/members/$id/reactivate", handle, withOrigin = withOrigin, withCsrf = withCsrf)

    private val adultBody = """{"firstName":"Edited","lastName":"Adult","dateOfBirth":"1991-03-04",""" +
        """"email":"edited@example.invalid","phone":"+41000000006","isPlayer":true,"gender":"other"}"""

    private class Check { val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
        }
        fun done() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    @Test
    fun bothRoutesAnswerSessionThenOriginAndCsrfThenCapabilityThenValidation() = app {
        val target = member()
        val archived = member(archived = true)
        val check = Check()
        check.expect("PUT no session", replace(target, null, adultBody), HttpStatusCode.Unauthorized)
        check.expect("reactivate no session", reactivate(archived, null), HttpStatusCode.Unauthorized)
        check.expect("PUT unknown session", replace(target, "ghost", adultBody), HttpStatusCode.Unauthorized)
        for ((label, o, c) in listOf(Triple("no Origin", null, csrf), Triple("wrong Origin", "https://evil.example", csrf),
            Triple("no CSRF", origin, null), Triple("wrong CSRF", origin, "y".repeat(43)))) {
            check.expect("PUT $label", replace(target, "editor", adultBody, withOrigin = o, withCsrf = c), HttpStatusCode.Forbidden)
            check.expect("reactivate $label", reactivate(archived, "editor", withOrigin = o, withCsrf = c), HttpStatusCode.Forbidden)
        }
        // Without EDIT_MEMBERS: 403 for any ID and body, never 400 or 404.
        for (handle in listOf("plain", "directory")) {
            for (id in listOf(target, UUID.randomUUID(), "not-a-uuid")) {
                check.expect("PUT $handle $id", replace(id, handle, "{garbage"), HttpStatusCode.Forbidden)
                check.expect("reactivate $handle $id", reactivate(id, handle), HttpStatusCode.Forbidden)
            }
        }
        // With it, malformed input is 400.
        for (id in listOf("not-a-uuid", "123")) {
            check.expect("PUT bad id $id", replace(id, "editor", adultBody), HttpStatusCode.BadRequest)
            check.expect("reactivate bad id $id", reactivate(id, "editor"), HttpStatusCode.BadRequest)
        }
        for ((label, body) in listOf("empty" to "", "array" to "[]", "missing birth date" to """{"firstName":"A","lastName":"B"}""",
            "bad date" to """{"firstName":"A","lastName":"B","dateOfBirth":"1990-13-01"}""",
            "future date" to """{"firstName":"A","lastName":"B","dateOfBirth":"2999-01-01"}""",
            "blank name" to """{"firstName":" ","lastName":"B","dateOfBirth":"2014-01-01"}""",
            "string flag" to """{"firstName":"A","lastName":"B","dateOfBirth":"2014-01-01","isPlayer":"yes"}""",
            "unknown key" to """{"firstName":"A","lastName":"B","dateOfBirth":"2014-01-01","role":"ADMIN"}""",
            "account key" to """{"firstName":"A","lastName":"B","dateOfBirth":"2014-01-01","accountId":"x"}""")) {
            check.expect("PUT $label", replace(target, "editor", body), HttpStatusCode.BadRequest)
        }
        check.done()
        assertEquals("Edit", sql { it.fetchValue("SELECT first_name FROM members WHERE id = ?", target) }, "no refusal changed anything")
        assertEquals(1, count("SELECT COUNT(*) FROM members WHERE id = ? AND archived_at IS NOT NULL", archived))
    }

    @Test
    fun replacementChangesEveryEditableFieldAndNothingElse() = app {
        val target = member()
        assertEquals(HttpStatusCode.NoContent, replace(target, "editor", adultBody).status)
        val row = sql { it.fetchOne("SELECT first_name, last_name, date_of_birth::text, email, phone, gender, is_player, role, account_id FROM members WHERE id = ?", target)!! }
        assertEquals(listOf("Edited", "Adult", "1991-03-04", "edited@example.invalid", "+41000000006", "other", true, "MEMBER", null),
            (0 until 9).map { row.get(it) })
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_UPDATED' AND target_member_id = ? AND actor_account_id = ?",
            target, accounts.getValue("editor")))

        // Omitted optional fields are cleared, which the database allows only for a minor.
        val minorBody = """{"firstName":"Now","lastName":"Minor","dateOfBirth":"2014-01-01"}"""
        assertEquals(HttpStatusCode.NoContent, replace(target, "editor", minorBody).status)
        val minor = sql { it.fetchOne("SELECT email, phone, gender, is_player FROM members WHERE id = ?", target)!! }
        assertEquals(listOf(null, null, null, false), (0 until 4).map { minor.get(it) })

        // The database rules stay authoritative and are 400; nothing changes.
        val adultWithoutPhone = """{"firstName":"No","lastName":"Phone","dateOfBirth":"1990-01-01","email":"x@example.invalid"}"""
        assertEquals(HttpStatusCode.BadRequest, replace(target, "editor", adultWithoutPhone).status)
        sql { it.execute("UPDATE clubs SET gender_required_for_players = TRUE WHERE id = ?", club) }
        try {
            val playerWithoutGender = """{"firstName":"No","lastName":"Gender","dateOfBirth":"2014-01-01","isPlayer":true}"""
            assertEquals(HttpStatusCode.BadRequest, replace(target, "editor", playerWithoutGender).status)
        } finally {
            sql { it.execute("UPDATE clubs SET gender_required_for_players = FALSE WHERE id = ?", club) }
        }
        assertEquals("Now", sql { it.fetchValue("SELECT first_name FROM members WHERE id = ?", target) })

        // The editor's own linked administrator record keeps its link and role.
        val self = sql { it.fetchValue("SELECT id FROM members WHERE account_id = ?", accounts.getValue("editor")) } as UUID
        assertEquals(HttpStatusCode.NoContent, replace(self, "editor",
            """{"firstName":"Http","lastName":"Actor","dateOfBirth":"1985-01-01","email":"a@example.invalid","phone":"+41000000001"}""").status)
        assertEquals(listOf<Any?>("ADMIN", accounts.getValue("editor")),
            sql { it.fetchOne("SELECT role, account_id FROM members WHERE id = ?", self)!!.let { r -> listOf(r.get(0), r.get(1)) } })
        assertEquals(1, count("SELECT COUNT(*) FROM club_capability_grants WHERE member_id = ?", self), "grants untouched")

        // Archived and unknown members are 404.
        assertEquals(HttpStatusCode.NotFound, replace(member(archived = true), "editor", adultBody).status)
        assertEquals(HttpStatusCode.NotFound, replace(UUID.randomUUID(), "editor", adultBody).status)
    }

    @Test
    fun aReplacementAppliesOnlyToTheVersionTheEditorRead() = app {
        val target = member()
        val details = send(HttpMethod.Get, "/api/v1/members/$target/details", "editor", withOrigin = null, withCsrf = null)
        assertEquals(HttpStatusCode.OK, details.status)
        val read = requireNotNull(details.headers[HttpHeaders.ETag]) { "an editor's read carries the version" }
        assertEquals(currentETag(target), read)

        // Missing is 428; malformed, a wildcard or another version is 412; none of them changes or audits anything.
        val check = Check()
        check.expect("no If-Match", replace(target, "editor", adultBody, ifMatch = null), HttpStatusCode.PreconditionRequired)
        for (value in listOf("*", "abc", "W/$read", "\"0\"", "\"-1\"", "\"99999999999999999999\"", "\"999\"")) {
            check.expect("If-Match $value", replace(target, "editor", adultBody, ifMatch = value), HttpStatusCode.PreconditionFailed)
        }
        // Authorization and validation still come first.
        check.expect("no grant", replace(target, "plain", adultBody, ifMatch = null), HttpStatusCode.Forbidden)
        check.expect("bad body", replace(target, "editor", "[]", ifMatch = null), HttpStatusCode.BadRequest)
        check.done()
        assertEquals("Edit", sql { it.fetchValue("SELECT first_name FROM members WHERE id = ?", target) })
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_UPDATED' AND target_member_id = ?", target))

        // A change in between, here a guardian-style contact update, makes the editor's version stale.
        sql { it.execute("UPDATE members SET phone = '+41000000099' WHERE id = ?", target) }
        val stale = replace(target, "editor", adultBody, ifMatch = read)
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
        assertEquals("changed", Json.parseToJsonElement(stale.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("+41000000099", sql { it.fetchValue("SELECT phone FROM members WHERE id = ?", target) })

        // With the current version it applies and returns the next one, which then works once.
        val saved = replace(target, "editor", adultBody)
        assertEquals(HttpStatusCode.NoContent, saved.status)
        val next = requireNotNull(saved.headers[HttpHeaders.ETag])
        assertEquals(currentETag(target), next)
        assertEquals(HttpStatusCode.NoContent, replace(target, "editor", adultBody, ifMatch = next).status)
        assertEquals(HttpStatusCode.PreconditionFailed, replace(target, "editor", adultBody, ifMatch = next).status)
        assertEquals(2, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_UPDATED' AND target_member_id = ?", target))
    }

    @Test
    fun reactivationRestoresOnlyActiveStatusAndRespectsCurrentPolicy() = app {
        val archived = member(birth = "2014-06-01", email = null, phone = null, archived = true)
        assertEquals(HttpStatusCode.NoContent, reactivate(archived, "editor").status)
        assertEquals(0, count("SELECT COUNT(*) FROM members WHERE id = ? AND archived_at IS NOT NULL", archived))
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_REACTIVATED' AND target_member_id = ? AND actor_account_id = ?",
            archived, accounts.getValue("editor")))
        assertEquals(0, count("SELECT COUNT(*) FROM club_capability_grants WHERE member_id = ?", archived), "no rights restored")
        assertEquals(HttpStatusCode.NotFound, reactivate(archived, "editor").status, "already active")
        assertEquals(HttpStatusCode.NotFound, reactivate(UUID.randomUUID(), "editor").status, "unknown")
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_REACTIVATED' AND target_member_id = ?", archived))

        // A record that no longer satisfies the club's current policy is 409 and stays archived. Such a record
        // is created with the field trigger off, as if the policy had changed after archiving.
        val stale = UUID.randomUUID().also { members += it }
        sql { tx ->
            tx.execute("ALTER TABLE members DISABLE TRIGGER members_validate")
            try {
                tx.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth, email, archived_at)
                    VALUES (?, 'Stale', 'Adult', DATE '1990-01-01', 's@example.invalid', now())""", stale)
            } finally {
                tx.execute("ALTER TABLE members ENABLE TRIGGER members_validate")
            }
        }
        val conflict = reactivate(stale, "editor")
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("no-store", conflict.headers[HttpHeaders.CacheControl])
        assertEquals(1, count("SELECT COUNT(*) FROM members WHERE id = ? AND archived_at IS NOT NULL", stale))
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_REACTIVATED' AND target_member_id = ?", stale))
    }
}
