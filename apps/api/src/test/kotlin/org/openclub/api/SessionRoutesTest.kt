package org.openclub.api

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SessionRoutesTest {
    private val person = UUID.fromString("01234567-1234-1234-1234-123456789abc")
    private val handle = "h".repeat(43)
    private val csrf = "c".repeat(43)
    private val origin = "http://localhost:8080"
    private val cookies = AuthCookiePolicy.localDevelopment()

    private inner class Sessions : BrowserSessionOperations {
        var fail = false
        var active = true
        var logoutCalls = 0
        override suspend fun read(handle: String?): ActiveSession? {
            check(!fail)
            return if (active && handle == this@SessionRoutesTest.handle) ActiveSession(person, csrf) else null
        }
        override suspend fun logout(handle: String?, csrfToken: String?): LogoutResult {
            logoutCalls++
            check(!fail)
            if (read(handle) == null) return LogoutResult.UNAUTHENTICATED
            if (csrfToken != csrf) return LogoutResult.FORBIDDEN
            active = false
            return LogoutResult.REVOKED
        }
    }

    @Test
    fun disabledByDefault() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/me").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/auth/logout").status)
    }

    @Test
    fun readsOnlyIdentityAndCsrfAndFailsClosed() = testApplication {
        val sessions = Sessions()
        application { module(cookiePolicy = cookies, sessions = sessions, publicOrigin = origin) }
        for (cookie in listOf("", "invalid", "x".repeat(43))) {
            val denied = client.get("/api/v1/auth/me") { header(HttpHeaders.Cookie, "openclub_dev_session=$cookie") }
            assertEquals(HttpStatusCode.Unauthorized, denied.status)
            assertEquals("no-store", denied.headers[HttpHeaders.CacheControl])
            assertFalse(denied.bodyAsText().contains(csrf))
        }
        val ok = client.get("/api/v1/auth/me") { header(HttpHeaders.Cookie, "openclub_dev_session=$handle") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("""{"personId":"$person","csrfToken":"$csrf"}""", ok.bodyAsText())
        sessions.fail = true
        val unavailable = client.get("/api/v1/auth/me") { header(HttpHeaders.Cookie, "openclub_dev_session=$handle") }
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertFalse(unavailable.bodyAsText().contains(csrf))
    }

    @Test
    fun rejectsOriginContentTypeAndBodyBeforeDatabase() = testApplication {
        val sessions = Sessions()
        application { module(cookiePolicy = cookies, sessions = sessions, publicOrigin = origin) }
        for (candidate in listOf(null, "null", "https://evil.example", "$origin/", "$origin.evil")) {
            val response = client.post("/api/v1/auth/logout") {
                if (candidate != null) header(HttpHeaders.Origin, candidate)
                header(HttpHeaders.ContentType, "application/json")
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(response.headers[HttpHeaders.SetCookie])
        }
        for ((type, body) in listOf("text/plain" to "{}", "application/json" to "",
            "application/json" to """{"unexpected":true}""", "application/json" to "[")) {
            val response = client.post("/api/v1/auth/logout") {
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.ContentType, type)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        assertEquals(0, sessions.logoutCalls)
    }

    @Test
    fun productionCookieAndBoundaryRequests() = testApplication {
        val sessions = Sessions()
        application { module(cookiePolicy = AuthCookiePolicy.production(), sessions = sessions, publicOrigin = "https://club.example") }
        suspend fun request(origins: List<String> = listOf("https://club.example"),
            tokens: List<String> = listOf(csrf), type: String? = "application/json", body: String = "{}") =
            client.post("/api/v1/auth/logout") {
                origins.forEach { header(HttpHeaders.Origin, it) }
                tokens.forEach { header("X-CSRF-Token", it) }
                if (type != null) header(HttpHeaders.ContentType, type)
                header(HttpHeaders.Cookie, "__Host-openclub_session=$handle")
                setBody(body)
            }
        assertEquals(HttpStatusCode.Forbidden, request(origins = listOf("https://club.example", "https://club.example")).status)
        assertEquals(HttpStatusCode.Forbidden, request(tokens = listOf(csrf, csrf)).status)
        assertEquals(HttpStatusCode.BadRequest, request(type = null).status)
        assertEquals(HttpStatusCode.BadRequest, request(body = " ".repeat(257) + "{}").status)
        assertTrue(sessions.active)
        val success = request(body = " \n{\t}\r\n")
        assertEquals(HttpStatusCode.NoContent, success.status)
        val cookie = success.headers[HttpHeaders.SetCookie]!!
        assertTrue(cookie.startsWith("__Host-openclub_session="))
        assertTrue(cookie.contains("Max-Age=0"))
        assertTrue(cookie.contains("Path=/", ignoreCase = true))
        assertTrue(cookie.contains("Secure", ignoreCase = true))
        assertTrue(cookie.contains("HttpOnly", ignoreCase = true))
        assertTrue(cookie.contains("SameSite=Lax", ignoreCase = true))
        assertFalse(cookie.contains("Domain=", ignoreCase = true))
    }

    @Test
    fun canonicalConfiguredOriginPermitsBrowserLogoutWithoutWeakeningExactMatch() = testApplication {
        val config = requireNotNull(OidcRuntimeConfig.fromEnvironment(mapOf(
            "OPENCLUB_AUTH_MODE" to "production",
            "OPENCLUB_OIDC_ISSUER" to "https://id.example/realms/openclub",
            "OPENCLUB_OIDC_AUTHORIZATION_ENDPOINT" to "https://id.example/realms/openclub/protocol/openid-connect/auth",
            "OPENCLUB_OIDC_REDIRECT_URI" to "https://CLUB.EXAMPLE:443/api/v1/auth/callback",
            "OPENCLUB_OIDC_CLIENT_ID" to "openclub-bff",
            "OPENCLUB_OIDC_CLIENT_SECRET" to "synthetic-test-secret-".repeat(3),
            "OPENCLUB_PUBLIC_ORIGIN" to "https://CLUB.EXAMPLE:443",
        )))
        val sessions = Sessions()
        application {
            module(cookiePolicy = config.cookies, sessions = sessions, publicOrigin = config.browserOrigin)
        }
        suspend fun logout(fromOrigin: String) = client.post("/api/v1/auth/logout") {
            header(HttpHeaders.Origin, fromOrigin)
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Cookie, "__Host-openclub_session=$handle")
            header("X-CSRF-Token", csrf)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, logout("https://CLUB.EXAMPLE:443").status)
        assertEquals(HttpStatusCode.Forbidden, logout("https://club.example.evil").status)
        assertTrue(sessions.active)
        assertEquals(HttpStatusCode.NoContent, logout("https://club.example").status)
        assertFalse(sessions.active)
    }

    @Test
    fun expandedIpv6ConfigurationAcceptsBrowserCompressedOriginOnly() = testApplication {
        val raw = "https://[2001:0db8:0:0:0:0:0:1]"
        val config = requireNotNull(OidcRuntimeConfig.fromEnvironment(mapOf(
            "OPENCLUB_AUTH_MODE" to "production",
            "OPENCLUB_OIDC_ISSUER" to "https://id.example/realms/openclub",
            "OPENCLUB_OIDC_AUTHORIZATION_ENDPOINT" to "https://id.example/realms/openclub/protocol/openid-connect/auth",
            "OPENCLUB_OIDC_REDIRECT_URI" to "$raw/api/v1/auth/callback",
            "OPENCLUB_OIDC_CLIENT_ID" to "openclub-bff",
            "OPENCLUB_OIDC_CLIENT_SECRET" to "synthetic-test-secret-".repeat(3),
            "OPENCLUB_PUBLIC_ORIGIN" to raw,
        )))
        val sessions = Sessions()
        application {
            module(cookiePolicy = config.cookies, sessions = sessions, publicOrigin = config.browserOrigin)
        }
        suspend fun logout(origin: String) = client.post("/api/v1/auth/logout") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Cookie, "__Host-openclub_session=$handle")
            header("X-CSRF-Token", csrf)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, logout(raw).status)
        assertEquals(HttpStatusCode.Forbidden, logout("https://[2001:db8::2]").status)
        assertTrue(sessions.active)
        assertEquals(HttpStatusCode.NoContent, logout("https://[2001:db8::1]").status)
        assertFalse(sessions.active)
    }

    @Test
    fun logoutRejectsCsrfRevokesAndDoesNotLieOnDatabaseFailure() = testApplication {
        val sessions = Sessions()
        application { module(cookiePolicy = cookies, sessions = sessions, publicOrigin = origin) }
        suspend fun logout(token: String?) = client.post("/api/v1/auth/logout") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Cookie, "openclub_dev_session=$handle")
            if (token != null) header("X-CSRF-Token", token)
            setBody("{}")
        }
        for (token in listOf(null, "", "x".repeat(43))) {
            val denied = logout(token)
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            assertNull(denied.headers[HttpHeaders.SetCookie])
            assertTrue(sessions.active)
        }
        sessions.fail = true
        val unavailable = logout(csrf)
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertNull(unavailable.headers[HttpHeaders.SetCookie])
        assertTrue(sessions.active)
        sessions.fail = false
        val success = logout(csrf)
        assertEquals(HttpStatusCode.NoContent, success.status)
        assertEquals("", success.bodyAsText())
        assertEquals("no-store", success.headers[HttpHeaders.CacheControl])
        assertTrue(success.headers[HttpHeaders.SetCookie]!!.contains("Max-Age=0"))
        assertEquals(HttpStatusCode.Unauthorized, logout(csrf).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me") {
            header(HttpHeaders.Cookie, "openclub_dev_session=$handle")
        }.status)
    }
}
