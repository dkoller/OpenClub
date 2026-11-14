package org.openclub.api

import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import com.nimbusds.oauth2.sdk.http.HTTPResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Internal confidential authorization-code redemption. A verified ID-token subject,
 * NOT a browser session or permission, is the only result. Never log codes/tokens/secrets.
 * Blocking network operation: future Ktor callers must use a bounded I/O dispatcher.
 */
internal class OidcAuthorizationCodeExchange(
    private val settings: OidcSettings,
    private val provider: OidcProviderDiscovery.Provider,
    private val clientSecret: String,
) {
    private val verifier = OidcIdTokenVerifier(settings, provider.publicKeys)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    init {
        require(clientSecret.isNotBlank()) { "A confidential OIDC client secret is required" }
        val endpoint = provider.tokenEndpoint
        require(!endpoint.isOpaque && !endpoint.host.isNullOrBlank() &&
            endpoint.scheme == settings.issuer.scheme &&
            endpoint.host.equals(settings.issuer.host, ignoreCase = true) &&
            endpoint.port == settings.issuer.port &&
            endpoint.userInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null) {
            "Token endpoint must match the trusted issuer origin"
        }
    }

    /** Called ONLY after atomically consuming a matching browser-bound state transaction. */
    fun redeem(code: String, login: ConsumedOidcLogin): VerifiedOidcIdentity {
        require(code.isNotBlank() && code.length <= 4096 && code.none { it.isISOControl() }) {
            "Invalid authorization code"
        }
        require(login.codeVerifier.length in 43..128) { "Invalid stored PKCE verifier" }
        require(login.nonce.length in 43..128) { "Invalid stored OIDC nonce" }

        val oauthRequest = TokenRequest(
            provider.tokenEndpoint,
            ClientSecretBasic(ClientID(settings.clientId), Secret(clientSecret)),
            AuthorizationCodeGrant(
                AuthorizationCode(code), settings.redirectUri, CodeVerifier(login.codeVerifier)
            ),
            null,
        ).toHTTPRequest()
        val authorization = requireNotNull(oauthRequest.authorization)
        val formBody = requireNotNull(oauthRequest.body)

        // Bounded, entire-response deadline: HttpClient.ofInputStream() alone
        // would leave body reads outside the request timeout.
        val request = HttpRequest.newBuilder(provider.tokenEndpoint)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", authorization)
            .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
            .build()
        val pending = http.sendAsync(request, HttpResponse.BodyHandler {
            LimitedOidcBodySubscriber(16 * 1024L, "OIDC token response too large")
        })
        val response = try {
            pending.get(5, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            pending.cancel(true)
            throw IllegalStateException("OIDC token endpoint timed out", e)
        } catch (e: InterruptedException) {
            pending.cancel(true)
            Thread.currentThread().interrupt()
            throw IllegalStateException("OIDC token exchange interrupted", e)
        } catch (e: ExecutionException) {
            throw IllegalStateException("OIDC token endpoint failed", e.cause ?: e)
        }

        // Never include the response body or provider error descriptions in exceptions.
        require(response.statusCode() == 200) { "OIDC token endpoint rejected authorization code" }
        val contentType = response.headers().firstValue("Content-Type").orElse("")
            .substringBefore(';').trim()
        require(contentType.equals("application/json", ignoreCase = true)) {
            "OIDC token response is not JSON"
        }
        val parsed = HTTPResponse(200).apply {
            setContentType("application/json")
            setBody(String(response.body(), StandardCharsets.UTF_8))
        }
        val tokenResponse = OIDCTokenResponseParser.parse(parsed)
        require(tokenResponse.indicatesSuccess()) { "OIDC token endpoint did not return tokens" }
        val tokens = (tokenResponse.toSuccessResponse() as OIDCTokenResponse).oidcTokens
        val idToken = requireNotNull(tokens.idToken) { "OIDC response missing ID token" }
        // Discard access/refresh tokens. Never persist or return them to the browser.
        return verifier.verify(idToken.serialize(), login.nonce)
    }
}
