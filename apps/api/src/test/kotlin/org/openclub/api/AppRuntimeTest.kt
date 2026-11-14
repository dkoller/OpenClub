package org.openclub.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class AppRuntimeTest {
    private val secret = "test-only-".repeat(5)
    private val prod = mapOf(
        "OPENCLUB_AUTH_MODE" to "production",
        "OPENCLUB_OIDC_ISSUER" to "https://id.example/realms/openclub",
        "OPENCLUB_OIDC_AUTHORIZATION_ENDPOINT" to "https://id.example/realms/openclub/protocol/openid-connect/auth",
        "OPENCLUB_OIDC_REDIRECT_URI" to "https://app.example/api/v1/auth/callback",
        "OPENCLUB_OIDC_CLIENT_ID" to "openclub-bff",
        "OPENCLUB_OIDC_CLIENT_SECRET" to secret,
        "OPENCLUB_PUBLIC_ORIGIN" to "https://app.example",
    )

    private val local = prod + mapOf(
        "OPENCLUB_AUTH_MODE" to "development",
        "OPENCLUB_OIDC_ISSUER" to "http://localhost:8180/realms/openclub-dev",
        "OPENCLUB_OIDC_AUTHORIZATION_ENDPOINT" to "http://localhost:8180/realms/openclub-dev/protocol/openid-connect/auth",
        "OPENCLUB_OIDC_REDIRECT_URI" to "http://localhost:5173/api/v1/auth/callback",
        "OPENCLUB_PUBLIC_ORIGIN" to "http://localhost:5173",
    )

    @Test
    fun disabledByDefaultAndNeverSilentlyIgnoresPartialCredentials() {
        assertNull(OidcRuntimeConfig.fromEnvironment(emptyMap()))
        assertNull(OidcRuntimeConfig.fromEnvironment(mapOf("OPENCLUB_AUTH_MODE" to "disabled")))
        assertThrows(IllegalArgumentException::class.java) {
            OidcRuntimeConfig.fromEnvironment(mapOf("OPENCLUB_OIDC_CLIENT_SECRET" to secret))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OidcRuntimeConfig.fromEnvironment(prod - "OPENCLUB_OIDC_CLIENT_SECRET")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OidcRuntimeConfig.fromEnvironment(prod + ("OPENCLUB_AUTH_MODE" to "invalid"))
        }
    }

    @Test
    fun productionCookiesUseSecureHostOnlyPrefixAndSeparateLoginBinding() {
        val runtime = requireNotNull(OidcRuntimeConfig.fromEnvironment(prod))
        assertEquals("OidcRuntimeConfig(redacted)", runtime.toString())
        assertFalse(runtime.toString().contains(secret))
        val cookie = runtime.cookies.session("a".repeat(43))
        val login = runtime.cookies.loginBinding("b".repeat(43))
        assertEquals("__Host-openclub_session", cookie.name)
        assertEquals("__Host-openclub_login", login.name)
        assertEquals(12 * 60 * 60, cookie.maxAge)
        assertEquals(5 * 60, login.maxAge)
        for (item in listOf(cookie, login, runtime.cookies.clearLogin(), runtime.cookies.clearSession())) {
            assertTrue(item.secure)
            assertTrue(item.httpOnly)
            assertEquals("/", item.path)
            assertNull(item.domain)
            assertEquals("Lax", item.extensions["SameSite"])
        }
        assertEquals(0, runtime.cookies.clearSession().maxAge)
        assertEquals("", runtime.cookies.clearSession().value)
    }

    @Test
    fun developmentCookiesAreLoopbackOnlyAndDoNotDowngradeProduction() {
        val runtime = requireNotNull(OidcRuntimeConfig.fromEnvironment(local))
        val cookie = runtime.cookies.session("a".repeat(43))
        assertEquals("openclub_dev_session", cookie.name)
        assertFalse(cookie.secure)
        assertTrue(cookie.httpOnly)
        assertEquals("/", cookie.path)
        assertNull(cookie.domain)
        assertThrows(IllegalArgumentException::class.java) {
            runtime.cookies.session("abc; Secure=false")
        }
        for (env in listOf(
            prod + ("OPENCLUB_PUBLIC_ORIGIN" to "http://app.example"),
            prod + ("OPENCLUB_OIDC_ISSUER" to "http://localhost:8180/realm"),
            prod + ("OPENCLUB_PUBLIC_ORIGIN" to "https://app.example/extra"),
            prod + ("OPENCLUB_OIDC_REDIRECT_URI" to "https://attacker.example/api/v1/auth/callback"),
            local + ("OPENCLUB_OIDC_ISSUER" to "http://id.internal/realm"),
            local + ("OPENCLUB_PUBLIC_ORIGIN" to "http://app.example"),
            local + ("OPENCLUB_OIDC_AUTHORIZATION_ENDPOINT" to "http://attacker.example/auth"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                OidcRuntimeConfig.fromEnvironment(env)
            }
        }
    }

    @Test
    fun browserOriginNormalizesUppercaseHostsAndDefaultPorts() {
        val uppercase = requireNotNull(OidcRuntimeConfig.fromEnvironment(prod + mapOf(
            "OPENCLUB_PUBLIC_ORIGIN" to "https://APP.EXAMPLE",
            "OPENCLUB_OIDC_REDIRECT_URI" to "https://APP.EXAMPLE/api/v1/auth/callback",
        )))
        assertEquals("https://app.example", uppercase.browserOrigin)

        val explicitDefaultPort = requireNotNull(OidcRuntimeConfig.fromEnvironment(prod + mapOf(
            "OPENCLUB_PUBLIC_ORIGIN" to "https://CLUB.EXAMPLE:443/",
            "OPENCLUB_OIDC_REDIRECT_URI" to "https://CLUB.EXAMPLE:443/api/v1/auth/callback",
        )))
        assertEquals("https://club.example", explicitDefaultPort.browserOrigin)

        val customPort = requireNotNull(OidcRuntimeConfig.fromEnvironment(prod + mapOf(
            "OPENCLUB_PUBLIC_ORIGIN" to "https://CLUB.EXAMPLE:8443",
            "OPENCLUB_OIDC_REDIRECT_URI" to "https://CLUB.EXAMPLE:8443/api/v1/auth/callback",
        )))
        assertEquals("https://club.example:8443", customPort.browserOrigin)

        val httpDefault = requireNotNull(OidcRuntimeConfig.fromEnvironment(local + mapOf(
            "OPENCLUB_PUBLIC_ORIGIN" to "http://LOCALHOST:80",
            "OPENCLUB_OIDC_REDIRECT_URI" to "http://LOCALHOST:80/api/v1/auth/callback",
        )))
        assertEquals("http://localhost", httpDefault.browserOrigin)
    }

    @Test
    fun browserOriginCanonicalizesIpv6AndKeepsExactPort() {
        fun config(origin: String, callback: String = origin.trimEnd('/') + "/api/v1/auth/callback") =
            requireNotNull(OidcRuntimeConfig.fromEnvironment(prod + mapOf(
                "OPENCLUB_PUBLIC_ORIGIN" to origin,
                "OPENCLUB_OIDC_REDIRECT_URI" to callback,
            )))
        assertEquals("https://[2001:db8::1]",
            config("https://[2001:0db8:0:0:0:0:0:1]").browserOrigin)
        assertEquals("https://[2001:db8::1]:8443",
            config("https://[2001:0DB8:0:0:0:0:0:1]:8443").browserOrigin)
        assertEquals("https://[::1]", config("https://[0:0:0:0:0:0:0:1]").browserOrigin)
        assertEquals("https://[2001:db8:0:1:1:1:1:1]",
            config("https://[2001:0DB8:0:1:1:1:1:1]").browserOrigin)
        assertThrows(IllegalArgumentException::class.java) {
            config("https://[fe80::1%25eth0]")
        }
    }

    @Test
    fun ownsBoundedPostgresPoolAndExecutesJdbcAwayFromCallerThread() = runBlocking {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        val env = mapOf(
            "DB_URL" to url!!,
            "DB_USER" to requireNotNull(System.getenv("DB_USER")),
            "DB_PASSWORD" to requireNotNull(System.getenv("DB_PASSWORD")),
        )
        val caller = Thread.currentThread()
        val runtime = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        runtime.use {
            val (worker, value) = runtime.onDatabaseThread { sql ->
                Thread.currentThread() to sql.fetchValue("SELECT 1")
            }
            assertNotSame(caller, worker, "JDBC must not execute on the test caller's thread")
            assertTrue(worker.name.startsWith("openclub-db-io"), "Unexpected JDBC worker: $worker")
            assertEquals("1", value.toString(), "PostgreSQL query should return scalar 1")
        }
    }

    @Test
    fun databaseConfigurationFailsClosed() {
        assertNull(DatabaseRuntime.openIfConfigured(emptyMap()))
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseRuntime.openIfConfigured(mapOf("DB_URL" to "jdbc:postgresql://localhost/db"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseRuntime.openIfConfigured(mapOf(
                "DB_URL" to "jdbc:mysql://localhost/db",
                "DB_USER" to "sample",
                "DB_PASSWORD" to "sample",
            ))
        }
    }
}
