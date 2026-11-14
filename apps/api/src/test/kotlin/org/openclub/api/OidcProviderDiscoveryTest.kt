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
    ): OidcProviderDiscovery = OidcProviderDiscovery(settings) { uri ->
        when (uri.toASCIIString()) {
            settings.issuer.toASCIIString() + "/.well-known/openid-configuration" -> document
            jwksUrl -> keys
            else -> error("Unexpected outbound OIDC request: $uri")
        }
    }

    private fun rejects(document: String) {
        assertThrows(Exception::class.java) { discovery(document).load() }
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
            discovery(keys = JWKSet(key).toString()).load()
        }
        assertThrows(Exception::class.java) {
            discovery(keys = """{"keys":[{"kty":"oct","k":"YWJj","kid":"symmetric"}]}""").load()
        }
        assertThrows(Exception::class.java) { discovery(keys = "invalid JSON").load() }
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
