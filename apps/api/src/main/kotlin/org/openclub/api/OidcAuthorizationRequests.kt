package org.openclub.api

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The first part of R05/C: prepare an authorization request without issuing a session.
 * These one-time secrets must later be bound to a server-side login transaction before
 * any HTTP redirect is exposed. Never use the values from a browser as trusted identity.
 */
internal data class OidcSettings(
    val issuer: URI,
    val authorizationEndpoint: URI,
    val clientId: String,
    val redirectUri: URI,
) {
    init {
        // URI.host can be null for hostless URLs and unparsed internationalized authorities.
        // Reject both rather than accidentally comparing two null hosts as equal.
        require(validWebAuthority(issuer)) { "Issuer must have a parsed host and HTTPS (or loopback HTTP)" }
        require(issuer.rawQuery == null && issuer.rawFragment == null)
        require(validWebAuthority(authorizationEndpoint)) { "Authorization endpoint needs a parsed host" }
        require(authorizationEndpoint.scheme == issuer.scheme &&
            authorizationEndpoint.host.equals(issuer.host, ignoreCase = true))
        require(authorizationEndpoint.port == issuer.port)
        require(authorizationEndpoint.rawFragment == null && authorizationEndpoint.rawQuery == null)
        require(clientId.isNotBlank())
        require(validWebAuthority(redirectUri)) { "Callback needs a parsed host and HTTPS (or loopback HTTP)" }
        require(redirectUri.rawQuery == null && redirectUri.rawFragment == null)
        require(redirectUri.path == "/api/v1/auth/callback")
    }

    private fun validWebAuthority(uri: URI): Boolean =
        !uri.isOpaque && uri.isAbsolute &&
            !uri.host.isNullOrBlank() && uri.rawAuthority != null && uri.userInfo == null &&
            (uri.scheme == "https" || isLoopbackHttp(uri))

    private fun isLoopbackHttp(uri: URI): Boolean =
        uri.scheme == "http" && (uri.host == "localhost" || uri.host == "127.0.0.1" || uri.host == "[::1]")
}

/** Not a session or authentication result: persist and bind this transaction before redirecting. */
internal data class OidcAuthorizationRequest(
    val url: URI,
    val state: String,
    val nonce: String,
    val codeVerifier: String,
)

internal class OidcAuthorizationRequests(
    private val config: OidcSettings,
    private val random: SecureRandom = SecureRandom(),
) {
    fun create(): OidcAuthorizationRequest {
        val state = secret()
        val nonce = secret()
        val verifier = secret()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        )
        val params = listOf(
            "response_type" to "code",
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri.toASCIIString(),
            "scope" to "openid",
            "state" to state,
            "nonce" to nonce,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return OidcAuthorizationRequest(
            URI.create("${config.authorizationEndpoint.toASCIIString()}?$params"),
            state,
            nonce,
            verifier,
        )
    }

    private fun secret(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
