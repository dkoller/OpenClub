package org.openclub.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserLoginOperations
import org.openclub.api.auth.IssuedSession
import org.openclub.api.auth.PendingOidcLogin
import org.openclub.api.auth.PostgresBrowserSessions
import org.openclub.api.database.DatabaseRuntime

/** Malformed-input matrix for the auth routes and the anonymous member list (#24). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthAndPublicRoutesInputTest {
    private var database: DatabaseRuntime? = null
    private val origin = "http://localhost:5173"
    private val cookies = AuthCookiePolicy.localDevelopment()

    @BeforeAll
    fun openDatabase() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
    }

    @AfterAll
    fun closeDatabase() {
        database?.close()
        database = null
    }

    private class FakeLogin : BrowserLoginOperations {
        var finishes = 0
        override suspend fun begin(): PendingOidcLogin = error("not used")
        override suspend fun finish(
            state: String, browserBinding: String?, code: String, previousSession: String?,
        ): IssuedSession? {
            finishes++
            return null
        }
    }

    /**
     * Session handles come straight from a cookie, so any bytes can arrive. Against the real
     * database-backed store, every garbage value must be an unauthenticated 401: never a 5xx, and
     * never a route running without a session.
     */
    @Test
    fun garbageSessionCookiesAreUnauthenticatedEverywhere() = testApplication {
        application {
            module(cookiePolicy = cookies, sessions = PostgresBrowserSessions(requireNotNull(database)),
                publicOrigin = origin, database = database)
        }
        val unknownButWellFormed = "A".repeat(43)
        val values = listOf(
            "" to "empty", "A".repeat(42) to "42 characters", unknownButWellFormed to "unknown 43 characters",
            "A".repeat(44) to "44 characters", "A".repeat(4000) to "4000 characters",
            "a b" to "space", "%C3%A9" to "percent-encoded", "\"quoted\"" to "quoted",
            "A".repeat(42) + "=" to "base64 padding", "../../etc/passwd" to "path",
            "' OR '1'='1" to "SQL fragment", "A".repeat(42) + "+" to "non-URL-safe base64",
        )
        val failures = mutableListOf<String>()
        suspend fun check(label: String, expected: HttpStatusCode, method: HttpMethod, path: String, cookie: String?,
                          body: String? = null) {
            val response = client.request(path) {
                this.method = method
                if (cookie != null) header(HttpHeaders.Cookie, cookie)
                if (method != HttpMethod.Get) {
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", "x".repeat(43))
                    header(HttpHeaders.ContentType, "application/json")
                }
                if (body != null) setBody(body)
            }
            val text = response.bodyAsText()
            if (response.status != expected) failures += "$label ($method $path): expected $expected but was ${response.status}"
            val raw = cookie?.substringAfter('=').orEmpty()
            if (raw.length > 8 && text.contains(raw)) failures += "$label ($method $path): reflected the cookie value"
        }
        for ((value, label) in values) {
            val cookie = "${cookies.sessionName}=$value"
            for (path in listOf("/api/v1/auth/me", "/api/v1/members", "/api/v1/teams", "/api/v1/club",
                "/api/v1/grants?memberId=00000000-0000-0000-0000-000000000000")) {
                check(label, HttpStatusCode.Unauthorized, HttpMethod.Get, path, cookie)
            }
            check(label, HttpStatusCode.Unauthorized, HttpMethod.Post, "/api/v1/auth/logout", cookie, "{}")
            check(label, HttpStatusCode.Unauthorized, HttpMethod.Post, "/api/v1/members", cookie,
                """{"firstName":"A","lastName":"B","dateOfBirth":"1990-01-01"}""")
        }
        check("no cookie", HttpStatusCode.Unauthorized, HttpMethod.Get, "/api/v1/auth/me", null)
        check("duplicate session cookies", HttpStatusCode.Unauthorized, HttpMethod.Get, "/api/v1/auth/me",
            "${cookies.sessionName}=$unknownButWellFormed; ${cookies.sessionName}=${"B".repeat(43)}")
        check("unrelated cookie only", HttpStatusCode.Unauthorized, HttpMethod.Get, "/api/v1/auth/me", "other=1")
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    @Test
    fun callbackRejectsExtraMalformedParametersBeforeCallingTheBackend() = testApplication {
        val fake = FakeLogin()
        application { module(fake, cookies) }
        val rejected = listOf(
            "oversized code" to "state=${"s".repeat(43)}&code=${"a".repeat(4097)}",
            "control character in code" to "state=${"s".repeat(43)}&code=ab%07cd",
            "NUL in code" to "state=${"s".repeat(43)}&code=ab%00cd",
            "blank state" to "state=%20&code=abc",
            "empty state" to "state=&code=abc",
            "empty error parameter" to "state=${"s".repeat(43)}&code=abc&error=",
            "duplicate error parameter" to "state=${"s".repeat(43)}&code=abc&error=a&error=b",
        )
        val failures = mutableListOf<String>()
        for ((label, query) in rejected) {
            val response = client.get("/api/v1/auth/callback?$query")
            val text = response.bodyAsText()
            if (response.status != HttpStatusCode.BadRequest) {
                failures += "$label: expected 400 but was ${response.status}"
            }
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
            if (text.contains("abc") || text.contains("ab%")) failures += "$label: reflected the code"
        }
        assertEquals(0, fake.finishes, "the backend must not run for a rejected callback")
        // At the limits the request is passed on; an unknown login is a plain 401, never a 5xx.
        for ((label, query) in listOf(
            "4096-character code" to "state=${"s".repeat(43)}&code=${"a".repeat(4096)}",
            "long state" to "state=${"s".repeat(3000)}&code=abc",
        )) {
            val response = client.get("/api/v1/auth/callback?$query")
            if (response.status != HttpStatusCode.Unauthorized) failures += "$label: expected 401 but was ${response.status}"
        }
        assertEquals(2, fake.finishes)
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    @Test
    fun logoutRejectsEveryNonEmptyBodyShapeBeforeTouchingTheStore() = testApplication {
        application {
            module(cookiePolicy = cookies, sessions = PostgresBrowserSessions(requireNotNull(database)),
                publicOrigin = origin, database = database)
        }
        val failures = mutableListOf<String>()
        for ((label, body) in listOf(
            "nested object" to """{"a":{"b":1}}""", "array" to "[]", "null" to "null", "string" to "\"{}\"",
            "trailing garbage" to "{}x", "two objects" to "{}{}", "number" to "1", "unterminated" to "{",
            "256-byte padding plus content" to " ".repeat(250) + """{"a":1}""",
        )) {
            val response = client.post("/api/v1/auth/logout") {
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.ContentType, "application/json")
                header(HttpHeaders.Cookie, "${cookies.sessionName}=${"A".repeat(43)}")
                header("X-CSRF-Token", "x".repeat(43))
                setBody(body)
            }
            if (response.status != HttpStatusCode.BadRequest) failures += "$label: expected 400 but was ${response.status}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    /** The anonymous list takes no input: stray parameters change nothing and other methods do nothing. */
    @Test
    fun anonymousMemberListIgnoresInputAndAcceptsOnlyGet() = testApplication {
        application { module(cookiePolicy = cookies, publicOrigin = origin, database = database) }
        val plain = client.get("/api/v1/public/members")
        assertEquals(HttpStatusCode.OK, plain.status)
        val failures = mutableListOf<String>()
        for (query in listOf("?q=x", "?limit=999", "?cursor=junk", "?email=a@b.c&phone=1", "?q=" + "a".repeat(5000),
            "?memberId=not-a-uuid", "?q=%00%07")) {
            val response = client.get("/api/v1/public/members$query")
            if (response.status != HttpStatusCode.OK) failures += "$query: expected 200 but was ${response.status}"
            else if (response.bodyAsText() != plain.bodyAsText()) failures += "$query: the response changed"
        }
        for (method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)) {
            val response = client.request("/api/v1/public/members") {
                this.method = method
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"firstName":"A","lastName":"B","dateOfBirth":"1990-01-01"}""")
            }
            if (response.status != HttpStatusCode.NotFound && response.status != HttpStatusCode.MethodNotAllowed) {
                failures += "$method: expected 404 or 405 but was ${response.status}"
            }
        }
        assertEquals(plain.bodyAsText(), client.get("/api/v1/public/members").bodyAsText(), "list changed")
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }
}
