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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    // Unlike ofInputStream(), this subscriber completes only after the entire body.
    // get(timeout) covers headers AND body; cancellation aborts a stalled exchange.
    val pending = http.sendAsync(request, HttpResponse.BodyHandler { LimitedOidcBodySubscriber() })
    val response = try {
        pending.get(5, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        pending.cancel(true)
        throw IllegalStateException("OIDC discovery body deadline exceeded", e)
    } catch (e: InterruptedException) {
        pending.cancel(true)
        Thread.currentThread().interrupt()
        throw IllegalStateException("OIDC discovery interrupted", e)
    } catch (e: ExecutionException) {
        throw (e.cause ?: e)
    }
    require(response.statusCode() == 200) { "OIDC discovery request failed" }
    return String(response.body(), StandardCharsets.UTF_8)
}

/** Prevent unbounded buffering even when Content-Length is absent or dishonest. */
internal class LimitedOidcBodySubscriber(
    private val maxBytes: Long = 256 * 1024L,
    private val sizeError: String = "OIDC discovery response too large",
) : HttpResponse.BodySubscriber<ByteArray> {
    private val delegate = HttpResponse.BodySubscribers.ofByteArray()
    private lateinit var subscription: Flow.Subscription
    private var bytesReceived = 0L

    override fun getBody(): CompletionStage<ByteArray> = delegate.body

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        delegate.onSubscribe(subscription)
    }

    override fun onNext(item: List<ByteBuffer>) {
        val incoming = item.sumOf { it.remaining().toLong() }
        if (incoming > maxBytes - bytesReceived) {
            subscription.cancel()
            delegate.onError(IllegalArgumentException(sizeError))
            return
        }
        bytesReceived += incoming
        delegate.onNext(item)
    }

    override fun onError(error: Throwable) = delegate.onError(error)
    override fun onComplete() = delegate.onComplete()
}
