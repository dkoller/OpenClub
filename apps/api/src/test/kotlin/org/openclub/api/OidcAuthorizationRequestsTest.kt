package org.openclub.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class OidcAuthorizationRequestsTest {
    private fun settings() = OidcSettings(
        issuer = URI("http://localhost:8180/realms/openclub-dev"),
        authorizationEndpoint = URI("http://localhost:8180/realms/openclub-dev/protocol/openid-connect/auth"),
        clientId = "openclub-bff",
        redirectUri = URI("http://localhost:5173/api/v1/auth/callback"),
    )

    @Test
    fun createsS256AuthorizationCodeRequestWithIndependentSecrets() {
        val generator = OidcAuthorizationRequests(settings())
        val first = generator.create()
        val second = generator.create()
        val params = first.url.rawQuery.split("&").associate {
            val (key, value) = it.split("=", limit = 2)
            key to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
        assertEquals("code", params["response_type"])
        assertEquals("openid", params["scope"])
        assertEquals("openclub-bff", params["client_id"])
        assertEquals("http://localhost:5173/api/v1/auth/callback", params["redirect_uri"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals(first.state, params["state"])
        assertEquals(first.nonce, params["nonce"])
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(first.codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        )
        assertEquals(expectedChallenge, params["code_challenge"])
        assertTrue(first.state.length >= 43)
        assertTrue(first.nonce.length >= 43)
        assertTrue(first.codeVerifier.length >= 43)
        assertNotEquals(first.state, first.nonce)
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
        assertFalse(first.url.toString().contains(first.codeVerifier))
    }

    @Test
    fun rejectsUntrustedOrUnsafeConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            settings().copy(issuer = URI("http://identity.example/realms/demo"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings().copy(authorizationEndpoint = URI("http://attacker.example/authorize"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings().copy(authorizationEndpoint = URI("http://localhost:9191/authorize"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings().copy(redirectUri = URI("http://remote.example/api/v1/auth/callback"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings().copy(redirectUri = URI("http://localhost:5173/unsafe"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings().copy(clientId = "  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings().copy(authorizationEndpoint = URI("http://localhost:8180/auth?next=evil"))
        }
    }
}
