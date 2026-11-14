package org.openclub.api

import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

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
        application { module() }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/login").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/callback").status)
    }

    @Test
    fun loginSetsOnlyShortLivedHttpOnlyBindingAndRedirectsToConfiguredIssuer() = testApplication {
        val fake = FakeLogin(pending)
        application { module(fake, cookies) }
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
        application { module(fake, cookies) }
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
            assertTrue(response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .any { it.contains("openclub_dev_login=") && it.contains("Max-Age=0") })
        }
        assertEquals(0, fake.finishes)
    }

    @Test
    fun missingOrIncorrectBindingCannotIssueASession() = testApplication {
        val fake = FakeLogin(pending).apply { result = IssuedSession("h".repeat(43), "c".repeat(43)) }
        application { module(fake, cookies) }
        val url = "/api/v1/auth/callback?state=${pending.authorization.state}&code=one-time-code"
        val absent = client.get(url)
        assertEquals(HttpStatusCode.Unauthorized, absent.status)
        assertFalse(absent.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.contains("openclub_dev_session=") })
        val incorrect = client.get(url) { headers.append(HttpHeaders.Cookie, "openclub_dev_login=${"x".repeat(43)}") }
        assertEquals(HttpStatusCode.Unauthorized, incorrect.status)
        assertEquals(2, fake.finishes)
    }

    @Test
    fun successfulCallbackIssuesOpaqueSessionAndClearsPreAuthCookie() = testApplication {
        val fake = FakeLogin(pending).apply { result = IssuedSession("h".repeat(43), "c".repeat(43)) }
        application { module(fake, cookies) }
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
}
