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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import org.openclub.api.checkContract

class BrowserLoginRoutesTest {
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val pending = PendingOidcLogin(
        OidcAuthorizationRequest(
            URI.create("http://localhost:8180/realms/test/auth?state=opaque"),
            "s".repeat(43), "n".repeat(43), "v".repeat(43),
        ),
        "b".repeat(43),
    )

    private class FakeLogin(private val pending: PendingOidcLogin) : BrowserLoginOperations {
        var starts = 0
        var finishes = 0
        var lastBinding: String? = null
        var lastPrevious: String? = null
        var result: IssuedSession? = null
        override suspend fun begin(): PendingOidcLogin {
            starts++
            return pending
        }
        override suspend fun finish(
            state: String, browserBinding: String?, code: String, previousSession: String?,
        ): IssuedSession? {
            finishes++
            lastBinding = browserBinding
            lastPrevious = previousSession
            return if (browserBinding == pending.browserBinding && state == pending.authorization.state) result else null
        }
    }

    @Test
    fun authRoutesAreNotAvailableByDefault() = testApplication {
        application { checkContract(); module() }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/login").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/callback").status)
    }

    @Test
    fun loginSetsOnlyShortLivedHttpOnlyBindingAndRedirectsToConfiguredIssuer() = testApplication {
        val fake = FakeLogin(pending)
        application { checkContract(); module(fake, cookies) }
        val response = createClient { followRedirects = false }.get("/api/v1/auth/login")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(pending.authorization.url.toASCIIString(), response.headers[HttpHeaders.Location])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val header = response.headers.getAll(HttpHeaders.SetCookie).orEmpty().joinToString(";")
        assertTrue(header.contains("openclub_dev_login="))
        assertTrue(header.contains("HttpOnly"))
        assertTrue(header.contains("Max-Age=300"))
        assertFalse(header.contains("openclub_dev_session="))
        assertEquals(1, fake.starts)
    }

    @Test
    fun callbackRejectsMissingDuplicateAndProviderErrorBeforeCallingBackend() = testApplication {
        val fake = FakeLogin(pending)
        application { checkContract(); module(fake, cookies) }
        val paths = listOf(
            "/api/v1/auth/callback",
            "/api/v1/auth/callback?state=a&state=b&code=c",
            "/api/v1/auth/callback?state=a&code=c&code=d",
            "/api/v1/auth/callback?state=a&code=c&error=access_denied",
            "/api/v1/auth/callback?state=a&code=",
        )
        for (path in paths) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.BadRequest, response.status, path)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
            assertEquals("""{"code":"invalid","message":"Invalid authentication response"}""", response.bodyAsText())
            assertTrue(response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .any { it.contains("openclub_dev_login=") && it.contains("Max-Age=0") })
        }
        assertEquals(0, fake.finishes)
    }

    @Test
    fun missingOrIncorrectBindingCannotIssueASession() = testApplication {
        val fake = FakeLogin(pending).apply { result = IssuedSession("h".repeat(43), "c".repeat(43)) }
        application { checkContract(); module(fake, cookies) }
        val url = "/api/v1/auth/callback?state=${pending.authorization.state}&code=one-time-code"
        val absent = client.get(url)
        assertEquals(HttpStatusCode.Unauthorized, absent.status)
        assertTrue(absent.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertEquals("""{"code":"unauthenticated","message":"Authentication rejected"}""", absent.bodyAsText())
        assertFalse(absent.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.contains("openclub_dev_session=") })
        val incorrect = client.get(url) { headers.append(HttpHeaders.Cookie, "openclub_dev_login=${"x".repeat(43)}") }
        assertEquals(HttpStatusCode.Unauthorized, incorrect.status)
        assertEquals("""{"code":"unauthenticated","message":"Authentication rejected"}""", incorrect.bodyAsText())
        assertEquals(2, fake.finishes)
    }

    @Test
    fun successfulCallbackIssuesOpaqueSessionAndClearsPreAuthCookie() = testApplication {
        val fake = FakeLogin(pending).apply { result = IssuedSession("h".repeat(43), "c".repeat(43)) }
        application { checkContract(); module(fake, cookies) }
        val response = createClient { followRedirects = false }.get(
            "/api/v1/auth/callback?state=${pending.authorization.state}&code=one-time-code"
        ) {
            headers.append(HttpHeaders.Cookie,
                "openclub_dev_login=${pending.browserBinding}; openclub_dev_session=${"p".repeat(43)}")
        }
        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/", response.headers[HttpHeaders.Location])
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        assertTrue(setCookies.any { it.contains("openclub_dev_login=") && it.contains("Max-Age=0") })
        assertTrue(setCookies.any { it.contains("openclub_dev_session=${"h".repeat(43)}") && it.contains("HttpOnly") })
        assertEquals(pending.browserBinding, fake.lastBinding)
        assertEquals("p".repeat(43), fake.lastPrevious)
    }

    @Test
    fun providerAndBackendFailuresReturnGenericJsonWithoutLeakingDetails() = testApplication {
        val operations = object : BrowserLoginOperations {
            override suspend fun begin(): PendingOidcLogin = error("sensitive provider diagnostic")
            override suspend fun finish(
                state: String, browserBinding: String?, code: String, previousSession: String?,
            ): IssuedSession? = error("sensitive exchange diagnostic")
        }
        application { checkContract(); module(operations, cookies) }
        val login = client.get("/api/v1/auth/login")
        assertEquals(HttpStatusCode.ServiceUnavailable, login.status)
        assertEquals("no-store", login.headers[HttpHeaders.CacheControl])
        assertTrue(login.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertEquals("""{"code":"unavailable","message":"Authentication unavailable"}""", login.bodyAsText())

        val callback = client.get("/api/v1/auth/callback?state=${"s".repeat(43)}&code=one-time-code")
        assertEquals(HttpStatusCode.ServiceUnavailable, callback.status)
        assertEquals("no-store", callback.headers[HttpHeaders.CacheControl])
        assertTrue(callback.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertEquals("""{"code":"unavailable","message":"Authentication unavailable"}""", callback.bodyAsText())
        assertTrue(callback.headers.getAll(HttpHeaders.SetCookie).orEmpty().any {
            it.contains("openclub_dev_login=") && it.contains("Max-Age=0")
        })
    }
}
