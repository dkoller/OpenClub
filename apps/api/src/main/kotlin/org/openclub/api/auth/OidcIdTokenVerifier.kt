package org.openclub.api.auth

import org.openclub.api.database.*
import org.openclub.api.members.*

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator

/**
 * Identity extracted only from a verified ID token. This does NOT grant club access or
 * create a browser session. Email and display-name claims are intentionally ignored.
 */
internal data class VerifiedOidcIdentity(val issuer: String, val subject: String)

/**
 * Verifies OIDC ID tokens using Nimbus, with one configured issuer/client and an
 * explicitly trusted JWKS snapshot. Never build the key set from token header URLs.
 * The code-exchange runtime supplies a bounded, refreshable issuer-pinned snapshot.
 */
internal class OidcIdTokenVerifier(
    private val settings: OidcSettings,
    trustedPublicKeys: JWKSet,
) {
    init {
        require(trustedPublicKeys.keys.isNotEmpty()) { "Trusted OIDC key set must not be empty" }
    }

    private val validator = IDTokenValidator(
        Issuer(settings.issuer.toASCIIString()),
        ClientID(settings.clientId),
        JWSAlgorithm.RS256,
        trustedPublicKeys,
    ).also { it.setMaxClockSkew(30) }

    /**
     * Called only after consuming a browser-bound one-time state transaction and
     * exchanging its authorization code at the configured provider's token endpoint.
     * Any exception rejects authentication; no fallback to unverified claims.
     */
    fun verify(compactIdToken: String, expectedNonce: String): VerifiedOidcIdentity {
        require(compactIdToken.length in 1..16_384) { "Invalid ID token length" }
        require(expectedNonce.length in 43..128) { "Invalid expected nonce" }
        // Explicitly reject "none", encrypted tokens and algorithm substitution.
        val token = SignedJWT.parse(compactIdToken)
        require(token.header.algorithm == JWSAlgorithm.RS256) { "Unexpected ID token algorithm" }
        val claims = validator.validate(token, Nonce(expectedNonce))
        // MVP supports a single audience; reject additional untrusted recipients.
        require(token.jwtClaimsSet.audience == listOf(settings.clientId)) { "Unexpected token audience" }
        // Nimbus IDTokenValidator does not enforce optional nbf or azp for this policy.
        val tokenClaims = token.jwtClaimsSet
        if (tokenClaims.claims.containsKey("nbf")) {
            val notBefore = tokenClaims.notBeforeTime
            require(notBefore != null && notBefore.time <= System.currentTimeMillis() + 30_000L) {
                "ID token is not yet valid"
            }
        }
        val authorizedParty = tokenClaims.getClaim("azp")
        require(authorizedParty == null || authorizedParty is String && authorizedParty == settings.clientId) {
            "Unexpected authorized party"
        }
        val subject = claims.subject?.value
        require(!subject.isNullOrBlank()) { "ID token requires a nonblank subject" }
        return VerifiedOidcIdentity(settings.issuer.toASCIIString(), subject)
    }
}
