package org.openclub.api.fees

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

/** Membership fees (OpenAPI 1.12.0): the treasurer's periods and fees, and each member's own view. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeeHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val kid = UUID.randomUUID()
    private val gone = UUID.randomUUID()
    private val tag = UUID.randomUUID().toString().take(8)
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }
    private fun count(query: String, vararg args: Any): Int = sql { (it.fetchValue(query, *args) as Number).toInt() }
    private fun audits(type: String, member: UUID) =
        count("SELECT COUNT(*) FROM audit_events WHERE event_type = ? AND target_member_id = ?", type, member)

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { tx ->
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Fee HTTP Club', ?)", club, "fee-http-$club")
            for (handle in listOf("treasurer", "editor", "guardian", "plain", "stranger")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "fee-$account")
                if (handle == "stranger") continue
                memberOf[handle] = UUID.randomUUID().also {
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                           VALUES (?, ?, ?, 'Fees', DATE '1980-01-01', 'f@example.invalid', '+41000000030')""", it, account, handle)
                }
            }
            tx.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'MANAGE_FEES')", memberOf.getValue("treasurer"))
            tx.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'EDIT_MEMBERS')", memberOf.getValue("editor"))
            tx.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Fees', DATE '2016-02-03')", kid)
            tx.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
            tx.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone, archived_at)
               VALUES (?, 'Gone', 'Fees', DATE '1970-01-01', 'g@example.invalid', '+41000000031', now())""", gone)
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                val all = (memberOf.values + kid + gone).toTypedArray()
                tx.execute("DELETE FROM fee_periods WHERE name LIKE ?", "Fee test $tag%")
                tx.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", all)
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", all)
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database)) }
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

    private class Check { val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
        }
        fun done() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    private fun period(name: String, amount: String = "15000", due: String = "2026-12-31") =
        """{"name":"$name","dueDate":"$due","amountCents":$amount}"""

    private suspend fun ApplicationTestBuilder.create(name: String): String {
        val response = send(HttpMethod.Post, "/api/v1/fee-periods", "treasurer", period(name))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json().getValue("id").jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.fees(periodId: String, status: String = "all") =
        send(HttpMethod.Get, "/api/v1/fee-periods/$periodId/fees?status=$status", "treasurer").json()
            .getValue("items").jsonArray.map { it.jsonObject }.associateBy { UUID.fromString(it.getValue("memberId").jsonPrimitive.content) }

    private fun fee(amount: Int, paidOn: String?) = """{"amountCents":$amount,"paidOn":${paidOn?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun theTreasurerManagesPeriodsAndFeesAndEveryChangeIsAudited() = app {
        // Test order is not fixed: count audit events from a clean slate for this class's own members.
        sql { it.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", (memberOf.values + kid + gone).toTypedArray()) }
        val plain = memberOf.getValue("plain")
        val check = Check()
        check.expect("no session", send(HttpMethod.Get, "/api/v1/fee-periods", null), HttpStatusCode.Unauthorized)
        check.expect("editor reads", send(HttpMethod.Get, "/api/v1/fee-periods", "editor"), HttpStatusCode.Forbidden)
        check.expect("editor creates, bad body", send(HttpMethod.Post, "/api/v1/fee-periods", "editor", "[]"), HttpStatusCode.Forbidden)
        check.expect("wrong origin", send(HttpMethod.Post, "/api/v1/fee-periods", "treasurer", period("Fee test $tag x"), withOrigin = "https://evil.example.invalid"), HttpStatusCode.Forbidden)
        for ((label, body) in listOf(
            "amount as text" to period("Fee test $tag x", "\"150\""), "negative" to period("Fee test $tag x", "-1"),
            "fraction" to period("Fee test $tag x", "1.5"), "too much" to period("Fee test $tag x", "10000001"),
            "blank name" to period("   "), "bad date" to period("Fee test $tag x", due = "2026-13-01"),
            "old date" to period("Fee test $tag x", due = "1999-12-31"),
            "extra key" to """{"name":"Fee test $tag x","dueDate":"2026-12-31","amountCents":1,"currency":"EUR"}""",
        )) check.expect(label, send(HttpMethod.Post, "/api/v1/fee-periods", "treasurer", body), HttpStatusCode.BadRequest)
        check.done()

        val id = create("Fee test $tag season")
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/v1/fee-periods", "treasurer", period("FEE TEST $tag SEASON")).status)
        // Every active member is included at the period's amount; the archived member is not.
        val all = fees(id)
        for (member in listOf(plain, kid, memberOf.getValue("guardian"))) assertEquals(15000, all.getValue(member).getValue("amountCents").jsonPrimitive.content.toInt())
        assertFalse(gone in all)
        assertEquals(1, audits("FEE_ASSIGNED", kid))
        val listed = send(HttpMethod.Get, "/api/v1/fee-periods", "treasurer").json().getValue("items").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
        assertEquals("0", listed.getValue("paid").jsonPrimitive.content)

        // Pay, change the amount, unpay, exclude and re-include: each change is its own audit event.
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/fee-periods/$id/fees/$kid", "treasurer", fee(10000, "2026-11-20")).status)
        assertEquals(1, audits("FEE_CHANGED", kid)); assertEquals(1, audits("FEE_PAID", kid))
        assertTrue(kid in fees(id, "paid")); assertFalse(kid in fees(id, "open"))
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/fee-periods/$id/fees/$kid", "treasurer", fee(10000, null)).status)
        assertEquals(1, audits("FEE_UNPAID", kid)); assertEquals(1, audits("FEE_CHANGED", kid))
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/fee-periods/$id/fees/$plain", "treasurer").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/fee-periods/$id/fees/$plain", "treasurer").status)
        assertEquals(1, audits("FEE_REMOVED", plain))
        assertEquals(JsonNull, fees(id, "excluded").getValue(plain).getValue("amountCents"))
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/fee-periods/$id/fees/$plain", "treasurer", fee(5000, null)).status)
        assertEquals(2, audits("FEE_ASSIGNED", plain))

        val check2 = Check()
        check2.expect("archived member", send(HttpMethod.Put, "/api/v1/fee-periods/$id/fees/$gone", "treasurer", fee(1, null)), HttpStatusCode.Conflict)
        check2.expect("unknown member", send(HttpMethod.Put, "/api/v1/fee-periods/$id/fees/${UUID.randomUUID()}", "treasurer", fee(1, null)), HttpStatusCode.NotFound)
        check2.expect("unknown period", send(HttpMethod.Put, "/api/v1/fee-periods/${UUID.randomUUID()}/fees/$kid", "treasurer", fee(1, null)), HttpStatusCode.NotFound)
        check2.expect("paidOn missing", send(HttpMethod.Put, "/api/v1/fee-periods/$id/fees/$kid", "treasurer", """{"amountCents":1}"""), HttpStatusCode.BadRequest)
        check2.expect("editor sets", send(HttpMethod.Put, "/api/v1/fee-periods/$id/fees/$kid", "editor", fee(1, null)), HttpStatusCode.Forbidden)
        check2.expect("bad status", send(HttpMethod.Get, "/api/v1/fee-periods/$id/fees?status=late", "treasurer"), HttpStatusCode.BadRequest)
        check2.expect("unknown period fees", send(HttpMethod.Get, "/api/v1/fee-periods/${UUID.randomUUID()}/fees", "treasurer"), HttpStatusCode.NotFound)
        check2.expect("rename", send(HttpMethod.Patch, "/api/v1/fee-periods/$id", "treasurer", """{"name":"Fee test $tag renamed","dueDate":"2027-01-31"}"""), HttpStatusCode.NoContent)
        check2.expect("rename unknown", send(HttpMethod.Patch, "/api/v1/fee-periods/${UUID.randomUUID()}", "treasurer", """{"name":"Fee test $tag y","dueDate":"2027-01-31"}"""), HttpStatusCode.NotFound)
        check2.expect("delete, wrong name", send(HttpMethod.Delete, "/api/v1/fee-periods/$id", "treasurer", """{"name":"Fee test $tag season"}"""), HttpStatusCode.Conflict)
        check2.done()

        // A revoked grant takes effect at the next request.
        sql { it.execute("DELETE FROM club_capability_grants WHERE member_id = ? AND capability = 'MANAGE_FEES'", memberOf.getValue("treasurer")) }
        try {
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/fee-periods", "treasurer").status)
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, "/api/v1/fee-periods/$id/fees/$kid", "treasurer", fee(1, null)).status)
        } finally {
            sql { it.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'MANAGE_FEES')", memberOf.getValue("treasurer")) }
        }
        val removedBefore = audits("FEE_REMOVED", kid)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/fee-periods/$id", "treasurer", """{"name":"Fee test $tag renamed"}""").status)
        assertEquals(removedBefore + 1, audits("FEE_REMOVED", kid))
        assertEquals(0, count("SELECT COUNT(*) FROM member_fees WHERE member_id = ?", kid))
    }

    @Test
    fun membersSeeTheirOwnAndTheirChildrensFeesAndTheExportHasThem() = app {
        val id = create("Fee test $tag own")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/fee-periods/$id/fees/$kid", "treasurer", fee(8000, "2026-11-01")).status)
        suspend fun mine(handle: String) = send(HttpMethod.Get, "/api/v1/me/fees", handle).json().getValue("items").jsonArray
            .map { it.jsonObject }.filter { it.getValue("periodId").jsonPrimitive.content == id }
        val guardian = mine("guardian")
        assertEquals(setOf(memberOf.getValue("guardian").toString(), kid.toString()),
            guardian.map { it.getValue("for").jsonObject.getValue("id").jsonPrimitive.content }.toSet())
        val child = guardian.single { it.getValue("for").jsonObject.getValue("id").jsonPrimitive.content == kid.toString() }
        assertEquals(setOf("periodId", "periodName", "dueDate", "amountCents", "paidOn", "for"), child.keys)
        assertEquals("8000", child.getValue("amountCents").jsonPrimitive.content)
        assertEquals("2026-11-01", child.getValue("paidOn").jsonPrimitive.content)
        assertEquals(listOf(memberOf.getValue("plain").toString()), mine("plain").map { it.getValue("for").jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/v1/me/fees", "stranger").status)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/v1/me/fees", null).status)

        // The member export lists the member's fees.
        val export = send(HttpMethod.Get, "/api/v1/members/$kid/export", "editor").json()
        val exported = export.getValue("fees").jsonArray.map { it.jsonObject }.single { it.getValue("periodId").jsonPrimitive.content == id }
        assertEquals(setOf("periodId", "periodName", "dueDate", "amountCents", "paidOn"), exported.keys)
        assertEquals("2026-11-01", exported.getValue("paidOn").jsonPrimitive.content)
    }
}
