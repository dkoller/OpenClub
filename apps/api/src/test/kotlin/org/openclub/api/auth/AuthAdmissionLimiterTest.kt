package org.openclub.api.auth

import org.openclub.api.database.*
import org.openclub.api.members.*
import org.openclub.api.*

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import org.openclub.api.checkContract

class AuthAdmissionLimiterTest {
    @Test
    fun sharedBudgetsRejectAndResetWithoutUnboundedPerClientState() {
        var now = 0L
        val limiter = AuthAdmissionLimiter(2, 1) { now }
        assertTrue(limiter.allowLogin())
        assertTrue(limiter.allowLogin())
        assertEquals(false, limiter.allowLogin())
        assertTrue(limiter.allowCallback())
        assertEquals(false, limiter.allowCallback())
        now += Duration.ofMinutes(1).toNanos()
        assertTrue(limiter.allowLogin())
        assertTrue(limiter.allowCallback())
    }

    @Test
    fun limitRejectsBeforeLoginOperationsAndKeepsCallbackCookieClearing() = testApplication {
        var starts = 0
        var finishes = 0
        val pending = PendingOidcLogin(
            OidcAuthorizationRequest(URI("http://localhost:8180/auth"), "s".repeat(43),
                "n".repeat(43), "v".repeat(43)),
            "b".repeat(43),
        )
        val operations = object : BrowserLoginOperations {
            override suspend fun begin(): PendingOidcLogin {
                starts++
                return pending
            }
            override suspend fun finish(
                state: String, browserBinding: String?, code: String, previousSession: String?,
            ): IssuedSession? {
                finishes++
                return null
            }
        }
        application { checkContract();
            module(operations, AuthCookiePolicy.localDevelopment(),
                authAdmission = AuthAdmissionLimiter(1, 1) { 0L })
        }
        val noRedirect = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.Found, noRedirect.get("/api/v1/auth/login").status)
        val deniedLogin = noRedirect.get("/api/v1/auth/login")
        assertEquals(HttpStatusCode.TooManyRequests, deniedLogin.status)
        assertEquals("60", deniedLogin.headers[HttpHeaders.RetryAfter])
        assertEquals("no-store", deniedLogin.headers[HttpHeaders.CacheControl])
        assertTrue(deniedLogin.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertEquals("""{"code":"rate_limited","message":"Authentication rate limited"}""",
            deniedLogin.bodyAsText())
        assertEquals(1, starts)

        val callback = "/api/v1/auth/callback?state=${"s".repeat(43)}&code=valid"
        assertEquals(HttpStatusCode.Unauthorized, noRedirect.get(callback).status)
        val deniedCallback = noRedirect.get(callback)
        assertEquals(HttpStatusCode.TooManyRequests, deniedCallback.status)
        assertEquals("60", deniedCallback.headers[HttpHeaders.RetryAfter])
        assertEquals("no-store", deniedCallback.headers[HttpHeaders.CacheControl])
        assertTrue(deniedCallback.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertEquals("""{"code":"rate_limited","message":"Authentication rate limited"}""",
            deniedCallback.bodyAsText())
        assertTrue(deniedCallback.headers.getAll(HttpHeaders.SetCookie).orEmpty().any {
            it.contains("openclub_dev_login=") && it.contains("Max-Age=0")
        })
        assertEquals(1, finishes)
    }
}
