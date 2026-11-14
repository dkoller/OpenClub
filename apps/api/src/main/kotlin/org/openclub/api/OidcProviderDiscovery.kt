package org.openclub.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Fetches metadata only from a preconfigured issuer and accepts OIDC endpoints
 * on that issuer's exact origin. Never derive any URL from request data or JWT headers.
 *
 * Blocking network I/O: invoke on a bounded I/O dispatcher, not a Ktor event loop.
 */
internal class OidcProviderDiscovery(
    private val settings: OidcSettings,
    private val fetchDocument: (URI) -> String = ::fetchOidcDocument,
) {
    internal data class Provider(
        val tokenEndpoint: URI,
        val publicKeys: JWKSet,
    )

    fun load(): Provider {
        val discoveryUri = URI.create(
            settings.issuer.toASCIIString().trimEnd('/') + "/.well-known/openid-configuration"
        )
        val metadata = OIDCProviderMetadata.parse(fetchDocument(discoveryUri))
        require(metadata.issuer.value == settings.issuer.toASCIIString()) { "OIDC issuer mismatch" }

        val authorization = requireNotNull(metadata.authorizationEndpointURI) { "Missing authorization endpoint" }
        val token = requireNotNull(metadata.tokenEndpointURI) { "Missing token endpoint" }
        val jwks = requireNotNull(metadata.jwkSetURI) { "Missing JWKS endpoint" }
        require(authorization == settings.authorizationEndpoint) { "Authorization endpoint mismatch" }
        require(secureEndpoint(authorization) && secureEndpoint(token) && secureEndpoint(jwks)) {
            "OIDC endpoint is outside the configured issuer origin"
        }
        require(metadata.idTokenJWSAlgs?.contains(JWSAlgorithm.RS256) == true) {
            "Issuer does not advertise the required RS256 ID-token algorithm"
        }
        val keys = JWKSet.parse(fetchDocument(jwks)).keys
        require(keys.isNotEmpty() && keys.none(JWK::isPrivate)) { "Missing or private provider JWK" }
        val verifyingKeys = keys.filterIsInstance<RSAKey>().filter {
            !it.keyID.isNullOrBlank() &&
                (it.keyUse == null || it.keyUse == KeyUse.SIGNATURE) &&
                (it.algorithm == null || it.algorithm == JWSAlgorithm.RS256)
        }
        require(verifyingKeys.isNotEmpty()) { "No suitable public RS256 signing key" }
        return Provider(token, JWKSet(verifyingKeys))
    }

    private fun secureEndpoint(uri: URI): Boolean =
        !uri.isOpaque && !uri.host.isNullOrBlank() && uri.rawAuthority != null &&
            uri.userInfo == null && uri.rawFragment == null && uri.rawQuery == null &&
            uri.scheme == settings.issuer.scheme &&
            uri.host.equals(settings.issuer.host, ignoreCase = true) &&
            uri.port == settings.issuer.port
}

/**
 * No redirects, bounded connection/request time and response bytes.
 * TLS verification is the JDK default; HTTP is possible only with explicit
 * loopback-only OidcSettings (for local Keycloak development).
 */
private fun fetchOidcDocument(uri: URI): String {
    val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    val request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(5))
        .header("Accept", "application/json")
        .GET()
        .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
    response.body().use { body ->
        require(response.statusCode() == 200) { "OIDC discovery request failed" }
        val bytes = body.readNBytes(256 * 1024 + 1)
        require(bytes.size <= 256 * 1024) { "OIDC discovery response too large" }
        return String(bytes, StandardCharsets.UTF_8)
    }
}
