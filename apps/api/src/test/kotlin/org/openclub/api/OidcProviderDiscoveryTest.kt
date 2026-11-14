package org.openclub.api

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets

class OidcProviderDiscoveryTest {
    private val issuer = "https://identity.example/realms/openclub"
    private val authUrl = "$issuer/protocol/openid-connect/auth"
    private val tokenUrl = "$issuer/protocol/openid-connect/token"
    private val jwksUrl = "$issuer/protocol/openid-connect/certs"
    private val key = RSAKeyGenerator(2048).keyID("key-1").generate()

    private fun settings(base: String = issuer): OidcSettings =
        OidcSettings(
            URI(base),
            URI("$base/protocol/openid-connect/auth"),
            "openclub-bff",
            URI("https://app.example/api/v1/auth/callback"),
        )

    private fun metadata(
        identity: String = issuer,
        authorization: String = authUrl,
        token: String = tokenUrl,
        jwks: String = jwksUrl,
        algorithms: String = "[\"RS256\"]",
    ) = """{
      "issuer":"$identity",
      "authorization_endpoint":"$authorization",
      "token_endpoint":"$token",
      "jwks_uri":"$jwks",
      "response_types_supported":["code"],
      "subject_types_supported":["public"],
      "id_token_signing_alg_values_supported":$algorithms
    }"""

    private fun discovery(
        document: String = metadata(),
        keys: String = JWKSet(key.toPublicJWK()).toString(),
        settings: OidcSettings = settings(),
        requestedUrls: MutableList<URI> = mutableListOf(),
    ): OidcProviderDiscovery = OidcProviderDiscovery(settings) { uri ->
        requestedUrls.add(uri)
        when (uri.toASCIIString()) {
            settings.issuer.toASCIIString() + "/.well-known/openid-configuration" -> document
            jwksUrl -> keys
            else -> error("Unexpected outbound OIDC request: $uri")
        }
    }

    private fun rejects(document: String) {
        val requestedUrls = mutableListOf<URI>()
        assertThrows(Exception::class.java) {
            discovery(document = document, requestedUrls = requestedUrls).load()
        }
        assertEquals(
            listOf(URI("$issuer/.well-known/openid-configuration")),
            requestedUrls,
            "Invalid provider metadata must be rejected before any JWKS fetch",
        )
    }

    @Test
    fun loadsOnlyMatchingIssuerOriginAndPublicVerificationKeys() {
        val provider = discovery().load()
        assertEquals(URI(tokenUrl), provider.tokenEndpoint)
        assertEquals(1, provider.publicKeys.keys.size)
        assertEquals("key-1", provider.publicKeys.keys.single().keyID)
        assertEquals(false, provider.publicKeys.keys.single().isPrivate)
    }

    @Test
    fun rejectsIssuerEndpointAndAlgorithmSubstitution() {
        rejects(metadata(identity = "https://attacker.example/realm"))
        rejects(metadata(authorization = "https://attacker.example/auth"))
        rejects(metadata(token = "https://attacker.example/token"))
        rejects(metadata(token = "https://identity.example:443/token"))
        rejects(metadata(token = "https://identity.example@attacker.example/token"))
        rejects(metadata(jwks = "https://attacker.example/keys"))
        rejects(metadata(jwks = "https:/certs"))
        rejects(metadata(jwks = "https://bücher.example/certs"))
        rejects(metadata(algorithms = "[\"HS256\"]"))
        rejects(metadata(algorithms = "[]"))
    }

    @Test
    fun rejectsMissingInvalidAndPrivateKeys() {
        assertThrows(Exception::class.java) { discovery(keys = """{"keys":[]}""").load() }
        assertThrows(Exception::class.java) {
            discovery(keys = JWKSet(key).toString(false)).load()
        }
        assertThrows(Exception::class.java) {
            discovery(keys = """{"keys":[{"kty":"oct","k":"YWJj","kid":"symmetric"}]}""").load()
        }
        assertThrows(Exception::class.java) { discovery(keys = "invalid JSON").load() }
    }

    @Test
    fun readsValidChunkedMetadataAndJwksAtLimitButRejectsOneByteOver() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}/realms/test"
        val validMetadata = metadata(
            identity = base,
            authorization = "$base/protocol/openid-connect/auth",
            token = "$base/protocol/openid-connect/token",
            jwks = "$base/protocol/openid-connect/certs",
        )
        val bytes = validMetadata.toByteArray(StandardCharsets.UTF_8)
        val maximumSize = 256 * 1024
        org.junit.jupiter.api.Assertions.assertTrue(bytes.size < maximumSize)
        val discoveryPayload = java.util.concurrent.atomic.AtomicReference(
            bytes + ByteArray(maximumSize - bytes.size) { ' '.code.toByte() }
        )
        assertEquals(maximumSize, discoveryPayload.get().size)

        // sendResponseHeaders(200, 0) uses chunked transfer encoding with no Content-Length.
        server.createContext("/realms/test/.well-known/openid-configuration") { exchange ->
            val payload = discoveryPayload.get()
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { stream ->
                var offset = 0
                while (offset < payload.size) {
                    val next = minOf(8192, payload.size - offset)
                    stream.write(payload, offset, next)
                    offset += next
                }
            }
        }
        server.createContext("/realms/test/protocol/openid-connect/certs") { exchange ->
            val payload = JWKSet(key.toPublicJWK()).toString().toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val client = OidcProviderDiscovery(settings(base))
            val provider = client.load()
            assertEquals(URI("$base/protocol/openid-connect/token"), provider.tokenEndpoint)
            assertEquals("key-1", provider.publicKeys.keys.single().keyID)

            discoveryPayload.set(discoveryPayload.get() + byteArrayOf(' '.code.toByte()))
            assertEquals(maximumSize + 1, discoveryPayload.get().size)
            val exception = assertThrows(Exception::class.java) { client.load() }
            val messages = generateSequence(exception as Throwable?) { it.cause }
                .map { it.message.orEmpty() }.toList()
            org.junit.jupiter.api.Assertions.assertTrue(
                messages.any { it.contains("OIDC discovery response too large") },
                "An oversized chunked body must fail because of the byte limit, not JSON parsing: $messages",
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun stalledResponseBodyDoesNotEscapeFiveSecondDeadline() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val releaseBody = java.util.concurrent.CountDownLatch(1)
        val headersSent = java.util.concurrent.CountDownLatch(1)
        server.createContext("/realms/test/.well-known/openid-configuration") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0) // Chunked body: no Content-Length.
                exchange.responseBody.use {
                    it.write('{'.code)
                    it.flush()
                    headersSent.countDown()
                    releaseBody.await(12, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: java.io.IOException) {
                // The client may close the connection when its deadline expires.
            } finally {
                headersSent.countDown()
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/realms/test"
            val started = System.nanoTime()
            assertThrows(Exception::class.java) {
                OidcProviderDiscovery(settings(base)).load()
            }
            val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            org.junit.jupiter.api.Assertions.assertTrue(headersSent.await(1, java.util.concurrent.TimeUnit.SECONDS))
            org.junit.jupiter.api.Assertions.assertTrue(
                elapsedMs in 4_000..8_000,
                "A stalled body must fail near five seconds, not wait for the server: ${elapsedMs}ms"
            )
        } finally {
            releaseBody.countDown()
            server.stop(0)
        }
    }

    @Test
    fun rejectsRedirectsAndOversizedDiscoveryResponsesOverHttp() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var responseCode = 302
        var payload = "{}"
        server.createContext("/realms/test/.well-known/openid-configuration") { exchange ->
            exchange.responseHeaders.add("Location", "https://attacker.example")
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(responseCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/realms/test"
            val client = OidcProviderDiscovery(settings(base))
            assertThrows(IllegalArgumentException::class.java) { client.load() }
            responseCode = 200
            payload = " ".repeat(256 * 1024 + 1)
            assertThrows(IllegalArgumentException::class.java) { client.load() }
        } finally {
            server.stop(0)
        }
    }
}
