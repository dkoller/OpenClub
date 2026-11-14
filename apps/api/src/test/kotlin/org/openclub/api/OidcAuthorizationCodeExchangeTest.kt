package org.openclub.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.Date

class OidcAuthorizationCodeExchangeTest {
    private val key = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val login = ConsumedOidcLogin("n".repeat(43), "v".repeat(43))

    private fun idToken(issuer: String, nonce: String = login.nonce): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer).subject("subject-123").audience("openclub-bff")
            .issueTime(Date.from(Instant.now().minusSeconds(10)))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("nonce", nonce).build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims)
        jwt.sign(RSASSASigner(key.toPrivateKey()))
        return jwt.serialize()
    }

    private fun withServer(
        handler: (HttpExchange, String) -> Unit,
        block: (base: String, exchange: OidcAuthorizationCodeExchange) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}/realms/test"
        server.createContext("/realms/test/protocol/openid-connect/token") { handler(it, base) }
        server.start()
        try {
            val settings = OidcSettings(
                URI(base),
                URI("$base/protocol/openid-connect/auth"),
                "openclub-bff",
                URI("http://127.0.0.1:5173/api/v1/auth/callback"),
            )
            val provider = OidcProviderDiscovery.Provider(
                URI("$base/protocol/openid-connect/token"), JWKSet(key.toPublicJWK())
            )
            block(base, OidcAuthorizationCodeExchange(settings, provider, "test-client-secret"))
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, type: String = "application/json") {
        exchange.responseHeaders.add("Content-Type", type)
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun tokens(idToken: String): String =
        """{"access_token":"synthetic-access","token_type":"Bearer","expires_in":300,"id_token":"$idToken"}"""

    @Test
    fun sendsConfidentialCodeGrantWithPkceAndValidatesIdToken() {
        var requestCount = 0
        withServer({ exchange, issuer ->
            requestCount++
            assertEquals("POST", exchange.requestMethod)
            assertEquals("/realms/test/protocol/openid-connect/token", exchange.requestURI.path)
            assertEquals(null, exchange.requestURI.rawQuery)
            assertEquals("application/x-www-form-urlencoded", exchange.requestHeaders.getFirst("Content-Type"))
            val basic = requireNotNull(exchange.requestHeaders.getFirst("Authorization"))
            assertTrue(basic.startsWith("Basic "))
            val credentials = String(Base64.getDecoder().decode(basic.removePrefix("Basic ")), StandardCharsets.UTF_8)
            assertEquals("openclub-bff:test-client-secret", credentials)
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val params = body.split("&").associate {
                val parts = it.split("=", limit = 2)
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
            }
            assertEquals("authorization_code", params["grant_type"])
            assertEquals("one-use-code", params["code"])
            assertEquals(login.codeVerifier, params["code_verifier"])
            assertEquals("http://127.0.0.1:5173/api/v1/auth/callback", params["redirect_uri"])
            assertFalse(body.contains("test-client-secret"))
            respond(exchange, 200, tokens(idToken(issuer)))
        }) { base, exchange ->
            assertEquals(VerifiedOidcIdentity(base, "subject-123"), exchange.redeem("one-use-code", login))
            assertEquals(1, requestCount)
        }
    }

    @Test
    fun rejectsInvalidNonceAndTokenErrorsWithoutExposingProviderSecrets() {
        withServer({ exchange, base ->
            respond(exchange, 200, tokens(idToken(base, nonce = "wrong-nonce")))
        }) { _, exchange ->
            assertThrows(Exception::class.java) { exchange.redeem("code", login) }
        }
        withServer({ exchange, _ ->
            respond(exchange, 400, """{"error":"invalid_grant","error_description":"secret-info"}""")
        }) { _, exchange ->
            val failure = assertThrows(IllegalArgumentException::class.java) { exchange.redeem("code", login) }
            assertFalse(failure.message.orEmpty().contains("secret-info"))
        }
    }

    @Test
    fun rejectsRedirectsMalformedResponsesAndOversizedBodies() {
        withServer({ exchange, _ ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:1/attacker")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }) { _, exchange ->
            assertThrows(IllegalArgumentException::class.java) { exchange.redeem("code", login) }
        }
        withServer({ exchange, _ -> respond(exchange, 200, "{}") }) { _, exchange ->
            assertThrows(Exception::class.java) { exchange.redeem("code", login) }
        }
        withServer({ exchange, _ -> respond(exchange, 200, "x".repeat(16 * 1024 + 1)) }) { _, exchange ->
            val error = assertThrows(Exception::class.java) { exchange.redeem("code", login) }
            val messages = generateSequence(error as Throwable?) { it.cause }.map { it.message.orEmpty() }.toList()
            assertTrue(messages.any { it.contains("OIDC token response too large") }, "$messages")
        }
        withServer({ exchange, base -> respond(exchange, 200, tokens(idToken(base)), "text/plain") }) { _, exchange ->
            assertThrows(IllegalArgumentException::class.java) { exchange.redeem("code", login) }
        }
    }

    @Test
    fun chunkedTokenResponseHonorsExactByteLimit() {
        val extraByte = java.util.concurrent.atomic.AtomicBoolean(false)
        withServer({ exchange, issuer ->
            val tokenJson = tokens(idToken(issuer))
            val length = 16 * 1024 + if (extraByte.get()) 1 else 0
            val padded = tokenJson + " ".repeat(length - tokenJson.toByteArray(StandardCharsets.UTF_8).size)
            val bytes = padded.toByteArray(StandardCharsets.UTF_8)
            assertEquals(length, bytes.size)
            exchange.sendResponseHeaders(200, 0) // Chunked: no Content-Length
            exchange.responseBody.use { stream ->
                var index = 0
                while (index < bytes.size) {
                    val n = minOf(2048, bytes.size - index)
                    stream.write(bytes, index, n)
                    index += n
                }
            }
        }) { issuer, exchange ->
            assertEquals(VerifiedOidcIdentity(issuer, "subject-123"), exchange.redeem("code-1", login))
            extraByte.set(true)
            val failure = assertThrows(Exception::class.java) { exchange.redeem("code-2", login) }
            val chain = generateSequence(failure as Throwable?) { it.cause }
                .map { it.message.orEmpty() }.toList()
            assertTrue(chain.any { it.contains("OIDC token response too large") }, "$chain")
        }
    }

    @Test
    fun tokenResponseBodyStallExpiresWholeResponseDeadline() {
        val release = java.util.concurrent.CountDownLatch(1)
        val headersSent = java.util.concurrent.CountDownLatch(1)
        withServer({ exchange, _ ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use {
                    it.write('{'.code)
                    it.flush()
                    headersSent.countDown()
                    release.await(12, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: java.io.IOException) {
                // Cancellation can close the connection before the test server finishes.
            } finally {
                headersSent.countDown()
            }
        }) { _, exchange ->
            try {
                val started = System.nanoTime()
                assertThrows(Exception::class.java) { exchange.redeem("code", login) }
                val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertTrue(headersSent.await(1, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(elapsedMs in 4_000..8_000, "A stalled token body must time out: ${elapsedMs}ms")
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun rejectsUnsafeTokenEndpointAndInvalidCodesBeforeNetwork() {
        val base = "https://trusted.example/realm"
        val settings = OidcSettings(
            URI(base), URI("$base/auth"), "openclub-bff",
            URI("https://app.example/api/v1/auth/callback")
        )
        assertThrows(IllegalArgumentException::class.java) {
            OidcAuthorizationCodeExchange(
                settings,
                OidcProviderDiscovery.Provider(
                    URI("https://attacker.example/token"), JWKSet(key.toPublicJWK())
                ),
                "test-client-secret",
            )
        }
        var hits = 0
        withServer({ exchange, _ -> hits++; respond(exchange, 400, "{}") }) { _, exchange ->
            assertThrows(IllegalArgumentException::class.java) { exchange.redeem("", login) }
            assertThrows(IllegalArgumentException::class.java) {
                exchange.redeem("code", login.copy(codeVerifier = "short"))
            }
            assertEquals(0, hits)
        }
    }
}
