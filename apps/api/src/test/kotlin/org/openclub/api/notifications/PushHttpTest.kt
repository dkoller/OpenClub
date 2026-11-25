package org.openclub.api.notifications

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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.openclub.api.module
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

/** Push (OpenAPI 1.33.0): subscriptions, the inbox the woken device reads, and which devices are woken. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PushHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val endpoint = "https://fcm.googleapis.com/fcm/send/${UUID.randomUUID()}"
    private val push: PushConfig = run {
        val out = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(out))
        try { main() } finally { System.setOut(previous) }
        val lines = out.toString().lines().filter { it.isNotBlank() }.associate { it.substringBefore('=') to it.substringAfter('=') }
        requireNotNull(PushConfig.fromEnvironment(lines + mapOf("OPENCLUB_PUSH_SUBJECT" to "mailto:club@example.invalid",
            "OPENCLUB_PUSH_HOSTS" to "fcm.googleapis.com")))
    }
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
        sql { db ->
            db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Push Club', ?)", club, "push-$club")
            for (handle in listOf("phone", "other")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                db.execute("INSERT INTO accounts (id) VALUES (?)", account)
                db.execute("""INSERT INTO account_identities (id, account_id, issuer, subject)
                              VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "push-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    // A minor needs no email; push alone must be enough to receive notifications.
                    db.execute("""INSERT INTO members (id, account_id, first_name, last_name, date_of_birth)
                                  VALUES (?, ?, ?, 'Push', CURRENT_DATE - 5000)""", it, account, handle)
                }
            }
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM email_outbox WHERE member_id = ANY(?::uuid[])", memberOf.values.toTypedArray())
                tx.execute("DELETE FROM announcements WHERE title = 'Push news'")
                tx.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", memberOf.values.toTypedArray())
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun app(notifier: Notifier = Notifier(null, origin, push), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database), notifier = notifier) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, handle: String?, body: String? = null): HttpResponse =
        client.request(path) {
            this.method = method
            if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            if (method != HttpMethod.Get) { header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf) }
            if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
        }

    private suspend fun HttpResponse.json(): JsonObject {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return Json.parseToJsonElement(bodyAsText()).jsonObject
    }

    @Test
    @Order(1)
    fun aDeviceSubscribesOnlyOnTheOperatorsPushHosts() = app {
        val status = send(HttpMethod.Get, "/api/v1/me/push", "phone").json()
        assertTrue(status.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(push.publicKey, status.getValue("publicKey").jsonPrimitive.content)
        for (bad in listOf("https://evil.example/x", "http://fcm.googleapis.com/x", "https://169.254.169.254/x")) {
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, "/api/v1/me/push-subscriptions", "phone", """{"endpoint":"$bad"}""").status, bad)
        }
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/me/push-subscriptions", "phone", """{"endpoint":"$endpoint"}""").status)
        assertEquals(1, send(HttpMethod.Get, "/api/v1/me/push", "phone").json().getValue("devices").jsonPrimitive.int)
        // At most five devices per member: the oldest are dropped.
        repeat(6) { send(HttpMethod.Put, "/api/v1/me/push-subscriptions", "other", """{"endpoint":"https://fcm.googleapis.com/fcm/send/other-$it"}""") }
        assertEquals(5, send(HttpMethod.Get, "/api/v1/me/push", "other").json().getValue("devices").jsonPrimitive.int)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/v1/me/push", null).status)
    }

    @Test
    @Order(2)
    fun aMessageWakesTheDeviceOnceAndTheDeviceReadsOnlyItsOwnInbox() = app {
        // A club-wide announcement is queued for both members, who have no email but a device.
        val notifier = Notifier(null, origin, push)
        sql { db ->
            val id = UUID.randomUUID()
            db.execute("INSERT INTO announcements (id, title, body, show_until) VALUES (?, 'Push news', 'Synthetic', CURRENT_DATE + 7)", id)
            notifier.announcementPosted(db, id)
        }
        val endpoints = sql { MailWorker.dueEndpoints(it, 100_000) }
        assertTrue(endpoint in endpoints, endpoints.toString())
        assertEquals(6, endpoints.size, "one device of the first member and five of the second")
        assertTrue(sql { MailWorker.dueEndpoints(it, 100_000) }.none { it == endpoint }, "each message wakes devices only once")
        val inbox = send(HttpMethod.Get, "/api/v1/me/push-inbox", "phone").json().getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(1, inbox.size)
        assertTrue(inbox.single().getValue("subject").jsonPrimitive.content.contains("Push news"))
        assertEquals(setOf("kind", "subject", "createdAt"), inbox.single().keys)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, "/api/v1/me/push-inbox?since=yesterday", "phone").status)
    }

    @Test
    @Order(3)
    fun unsubscribingAndPushOff() = app {
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/me/push-subscriptions", "phone", """{"endpoint":"$endpoint"}""").status)
        assertEquals(0, send(HttpMethod.Get, "/api/v1/me/push", "phone").json().getValue("devices").jsonPrimitive.int)
    }

    @Test
    @Order(4)
    fun withoutPushConfigurationNothingSubscribes() = app(Notifier.DISABLED) {
        assertEquals(false, send(HttpMethod.Get, "/api/v1/me/push", "phone").json().getValue("enabled").jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Put, "/api/v1/me/push-subscriptions", "phone", """{"endpoint":"$endpoint"}""").status)
    }
}
