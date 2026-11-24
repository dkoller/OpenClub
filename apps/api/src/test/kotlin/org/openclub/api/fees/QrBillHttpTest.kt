package org.openclub.api.fees

import io.ktor.client.call.body
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
import net.codecrete.qrbill.generator.Payments
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

/** Swiss QR bills (OpenAPI 1.26.0): the club's payment details and the PDF of an open fee. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QrBillHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val kid = UUID.randomUUID()
    private val tag = UUID.randomUUID().toString().take(8)
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { tx ->
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'QR bill club', ?)", club, "qr-bill-$club")
            for (handle in listOf("treasurer", "editor", "guardian", "plain", "stranger")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "qr-$account")
                if (handle == "stranger") continue
                memberOf[handle] = UUID.randomUUID().also {
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                           VALUES (?, ?, ?, 'Bills', DATE '1980-01-01', 'q@example.invalid', '+41000000040')""", it, account, handle)
                }
            }
            tx.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'MANAGE_FEES')", memberOf.getValue("treasurer"))
            tx.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'EDIT_MEMBERS')", memberOf.getValue("editor"))
            tx.execute("INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Kiddo', 'Bills', DATE '2016-02-03')", kid)
            tx.execute("INSERT INTO member_guardianships (minor_member_id, guardian_member_id) VALUES (?, ?)", kid, memberOf.getValue("guardian"))
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                val all = (memberOf.values + kid).toTypedArray()
                tx.execute("DELETE FROM fee_periods WHERE name LIKE ?", "QR test $tag%")
                tx.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", all)
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", all)
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                // The payment details go with the club.
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
        suspend fun expect(label: String, response: HttpResponse, status: HttpStatusCode, code: String? = null) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
            if (code != null && !response.bodyAsText().contains("\"$code\"")) failures += "$label: expected code $code"
        }
        fun done() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    private val normalIban = "CH93 0076 2011 6238 5295 7"
    private val qrIban = "CH44 3199 9123 0008 8901 2"

    private fun details(iban: String = normalIban, holder: String = "FC Beispiel", street: String = "\"Seeweg\"",
                        number: String = "\"3\"", postal: String = "8000", town: String = "Zürich", country: String = "CH") =
        """{"iban":"$iban","holderName":"$holder","street":$street,"buildingNumber":$number,"postalCode":"$postal","town":"$town","country":"$country"}"""

    @Test
    fun treasurersReadAndReplaceThePaymentDetails() = app {
        sql { it.execute("DELETE FROM club_payment_details") }
        val path = "/api/v1/club/payment-details"
        val check = Check()
        check.expect("no session", send(HttpMethod.Get, path, null), HttpStatusCode.Unauthorized)
        check.expect("no session, put", send(HttpMethod.Put, path, null, details()), HttpStatusCode.Unauthorized)
        check.expect("editor reads", send(HttpMethod.Get, path, "editor"), HttpStatusCode.Forbidden)
        check.expect("member reads", send(HttpMethod.Get, path, "plain"), HttpStatusCode.Forbidden)
        check.expect("stranger reads", send(HttpMethod.Get, path, "stranger"), HttpStatusCode.Forbidden)
        check.expect("editor writes, bad body", send(HttpMethod.Put, path, "editor", "[]"), HttpStatusCode.Forbidden)
        check.expect("wrong origin", send(HttpMethod.Put, path, "treasurer", details(), withOrigin = "https://evil.example.invalid"), HttpStatusCode.Forbidden)
        check.expect("no csrf", send(HttpMethod.Put, path, "treasurer", details(), withCsrf = null), HttpStatusCode.Forbidden)
        check.expect("nothing set yet", send(HttpMethod.Get, path, "treasurer"), HttpStatusCode.NotFound)
        for ((label, body) in listOf(
            "bad checksum" to details(iban = "CH93 0076 2011 6238 5295 8"),
            "German IBAN" to details(iban = "DE89 3704 0044 0532 0130 00"),
            "holder too long" to details(holder = "x".repeat(71)),
            "blank holder" to details(holder = " "),
            "street too long" to details(street = "\"${"s".repeat(71)}\""),
            "building number too long" to details(number = "\"${"1".repeat(17)}\""),
            "building number without street" to details(street = "null"),
            "postal code too long" to details(postal = "1".repeat(17)),
            "town too long" to details(town = "t".repeat(36)),
            "other country" to details(country = "DE"),
            "iban as number" to """{"iban":1,"holderName":"FC","postalCode":"8000","town":"Bern","country":"CH"}""",
            "missing town" to """{"iban":"$normalIban","holderName":"FC","postalCode":"8000","country":"CH"}""",
            "extra key" to """{"iban":"$normalIban","holderName":"FC","postalCode":"8000","town":"Bern","country":"CH","bic":"X"}""",
        )) check.expect(label, send(HttpMethod.Put, path, "treasurer", body), HttpStatusCode.BadRequest)
        check.done()
        assertEquals(0, sql { (it.fetchValue("SELECT COUNT(*) FROM club_payment_details") as Number).toInt() })
        fun audited() = sql { (it.fetchValue(
            "SELECT COUNT(*) FROM audit_events WHERE event_type = 'PAYMENT_DETAILS_CHANGED' AND actor_account_id = ?",
            accounts.getValue("treasurer")) as Number).toInt() }
        val auditedBefore = audited()

        // Street and building number are optional; spaces in the IBAN are dropped.
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, path, "treasurer",
            """{"iban":"$normalIban","holderName":"FC Beispiel","postalCode":"8000","town":"Zürich","country":"CH"}""").status)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, path, "treasurer", details()).status)
        assertEquals(auditedBefore + 2, audited(), "every change of the account members pay to is audited")
        val read = send(HttpMethod.Get, path, "treasurer")
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(Json.parseToJsonElement("""{"iban":"CH9300762011623852957","qrIban":false,"holderName":"FC Beispiel",
            "street":"Seeweg","buildingNumber":"3","postalCode":"8000","town":"Zürich","country":"CH"}"""), read.json())
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, path, "treasurer", details(iban = qrIban, street = "null", number = "null")).status)
        val qr = send(HttpMethod.Get, path, "treasurer").json()
        assertEquals("true", qr.getValue("qrIban").jsonPrimitive.content)
        assertEquals(JsonNull, qr.getValue("street"))
        assertEquals(1, sql { (it.fetchValue("SELECT COUNT(*) FROM club_payment_details") as Number).toInt() })

        // A revoked grant takes effect at the next request.
        sql { it.execute("DELETE FROM club_capability_grants WHERE member_id = ? AND capability = 'MANAGE_FEES'", memberOf.getValue("treasurer")) }
        try {
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, path, "treasurer").status)
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, path, "treasurer", details()).status)
        } finally {
            sql { it.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'MANAGE_FEES')", memberOf.getValue("treasurer")) }
        }
    }

    @Test
    fun membersAndGuardiansDownloadTheBillOfAnOpenFee() = app {
        sql { it.execute("DELETE FROM club_payment_details") }
        val created = send(HttpMethod.Post, "/api/v1/fee-periods", "treasurer",
            """{"name":"QR test $tag season","dueDate":"2026-12-31","amountCents":15050}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val period = created.json().getValue("id").jsonPrimitive.content
        val plain = memberOf.getValue("plain")
        fun bill(member: UUID, query: String = "") = "/api/v1/me/fees/$period/$member/qr-bill$query"
        suspend fun qrBillFlag(handle: String, member: UUID) = send(HttpMethod.Get, "/api/v1/me/fees", handle).json()
            .getValue("items").jsonArray.map { it.jsonObject }
            .single { it.getValue("periodId").jsonPrimitive.content == period && it.getValue("for").jsonObject.getValue("id").jsonPrimitive.content == member.toString() }
            .getValue("qrBill").jsonPrimitive.content

        val check = Check()
        check.expect("no details yet", send(HttpMethod.Get, bill(plain), "plain"), HttpStatusCode.Conflict, "no_payment_details")
        check.done()
        assertEquals("false", qrBillFlag("plain", plain))
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/club/payment-details", "treasurer", details()).status)
        assertEquals("true", qrBillFlag("plain", plain))
        assertEquals("true", qrBillFlag("guardian", kid))

        val own = send(HttpMethod.Get, bill(plain, "?lang=de"), "plain")
        assertEquals(HttpStatusCode.OK, own.status, own.bodyAsText())
        assertTrue(own.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/pdf"))
        assertTrue(own.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("attachment"))
        assertEquals("no-store", own.headers[HttpHeaders.CacheControl])
        assertEquals("%PDF", own.body<ByteArray>().copyOfRange(0, 4).toString(Charsets.US_ASCII))
        val child = send(HttpMethod.Get, bill(kid, "?lang=fr"), "guardian")
        assertEquals(HttpStatusCode.OK, child.status, child.bodyAsText())
        assertEquals("%PDF", child.body<ByteArray>().copyOfRange(0, 4).toString(Charsets.US_ASCII))

        val check2 = Check()
        check2.expect("no session", send(HttpMethod.Get, bill(plain), null), HttpStatusCode.Unauthorized)
        check2.expect("stranger", send(HttpMethod.Get, bill(plain), "stranger"), HttpStatusCode.Forbidden)
        check2.expect("stranger, bad input", send(HttpMethod.Get, "/api/v1/me/fees/x/y/qr-bill", "stranger"), HttpStatusCode.Forbidden)
        check2.expect("someone else's fee", send(HttpMethod.Get, bill(memberOf.getValue("guardian")), "plain"), HttpStatusCode.Forbidden)
        check2.expect("another guardian's child", send(HttpMethod.Get, bill(kid), "plain"), HttpStatusCode.Forbidden)
        check2.expect("the treasurer for a member", send(HttpMethod.Get, bill(plain), "treasurer"), HttpStatusCode.Forbidden)
        check2.expect("unknown member", send(HttpMethod.Get, bill(UUID.randomUUID()), "plain"), HttpStatusCode.Forbidden)
        check2.expect("bad period", send(HttpMethod.Get, "/api/v1/me/fees/x/$plain/qr-bill", "plain"), HttpStatusCode.BadRequest)
        check2.expect("bad language", send(HttpMethod.Get, bill(plain, "?lang=rm"), "plain"), HttpStatusCode.BadRequest)
        check2.expect("two languages", send(HttpMethod.Get, bill(plain, "?lang=de&lang=fr"), "plain"), HttpStatusCode.BadRequest)
        check2.expect("extra query", send(HttpMethod.Get, bill(plain, "?lang=de&x=1"), "plain"), HttpStatusCode.BadRequest)
        check2.expect("unknown period", send(HttpMethod.Get, "/api/v1/me/fees/${UUID.randomUUID()}/$plain/qr-bill", "plain"), HttpStatusCode.NotFound)
        check2.done()

        // Paid, nothing to pay and excluded fees have no bill.
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/fee-periods/$period/fees/$kid", "treasurer", """{"amountCents":15050,"paidOn":"2026-11-20"}""").status)
        val check3 = Check()
        check3.expect("paid", send(HttpMethod.Get, bill(kid), "guardian"), HttpStatusCode.Conflict, "not_open")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/fee-periods/$period/fees/$plain", "treasurer", """{"amountCents":0,"paidOn":null}""").status)
        check3.expect("nothing to pay", send(HttpMethod.Get, bill(plain), "plain"), HttpStatusCode.Conflict, "not_open")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/fee-periods/$period/fees/$plain", "treasurer").status)
        check3.expect("excluded", send(HttpMethod.Get, bill(plain), "plain"), HttpStatusCode.NotFound)
        check3.done()
        assertEquals("false", qrBillFlag("guardian", kid))
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/fee-periods/$period/fees/$plain", "treasurer", """{"amountCents":15050,"paidOn":null}""").status)

        // The treasurer sees the same reference per member: a creditor reference for a normal IBAN, a QR reference for a QR-IBAN.
        val periodNo = sql { (it.fetchValue("SELECT reference_no FROM fee_periods WHERE id = ?::uuid", period) as Number).toInt() }
        val feeNo = sql { (it.fetchValue("SELECT reference_no FROM member_fees WHERE period_id = ?::uuid AND member_id = ?", period, plain) as Number).toInt() }
        suspend fun referenceOf(member: UUID, status: String = "all") = send(HttpMethod.Get, "/api/v1/fee-periods/$period/fees?status=$status", "treasurer").json()
            .getValue("items").jsonArray.map { it.jsonObject }.single { it.getValue("memberId").jsonPrimitive.content == member.toString() }
            .getValue("reference")
        val creditor = referenceOf(plain).jsonPrimitive.content
        assertEquals(SwissPayments.display(SwissPayments.creditorReference(periodNo, feeNo)), creditor)
        assertTrue(Payments.isValidISO11649Reference(creditor), creditor)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/club/payment-details", "treasurer", details(iban = qrIban)).status)
        val qrReference = referenceOf(plain).jsonPrimitive.content
        assertEquals(SwissPayments.display(SwissPayments.qrReference(periodNo, feeNo)), qrReference)
        assertTrue(Payments.isValidQRReference(qrReference), qrReference)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, bill(plain, "?lang=it"), "plain").status)
        // An excluded member has no reference.
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/fee-periods/$period/fees/${memberOf.getValue("editor")}", "treasurer").status)
        assertEquals(JsonNull, referenceOf(memberOf.getValue("editor"), "excluded"))
        sql { it.execute("DELETE FROM club_payment_details") }
        assertEquals(JsonNull, referenceOf(plain))
    }
}
