package org.openclub.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.Date

class OidcIdTokenVerifierTest {
    private val issuer = "https://identity.example/realms/openclub"
    private val clientId = "openclub-bff"
    private val nonce = "n".repeat(43)
    private val key = RSAKeyGenerator(2048).keyID("trusted-key").generate()
    private val verifier = OidcIdTokenVerifier(
        OidcSettings(
            URI(issuer),
            URI("$issuer/protocol/openid-connect/auth"),
            clientId,
            URI("https://app.example/api/v1/auth/callback"),
        ),
        JWKSet(key.toPublicJWK()),
    )

    private fun claims(
        tokenIssuer: String = issuer,
        audience: List<String> = listOf(clientId),
        tokenNonce: String? = nonce,
        subject: String = "provider-subject",
        expiresAt: Instant = Instant.now().plusSeconds(300),
        notBefore: Instant? = null,
        authorizedParty: Any? = null,
    ): JWTClaimsSet {
        val builder = JWTClaimsSet.Builder()
            .issuer(tokenIssuer)
            .subject(subject)
            .audience(audience)
            .issueTime(Date.from(Instant.now().minusSeconds(10)))
            .expirationTime(Date.from(expiresAt))
        if (tokenNonce != null) builder.claim("nonce", tokenNonce)
        if (notBefore != null) builder.notBeforeTime(Date.from(notBefore))
        if (authorizedParty != null) builder.claim("azp", authorizedParty)
        return builder.build()
    }

    private fun signed(
        jwtClaims: JWTClaimsSet = claims(),
        signingKey: RSAKey = key,
        algorithm: JWSAlgorithm = JWSAlgorithm.RS256,
    ): String {
        val jwt = SignedJWT(JWSHeader.Builder(algorithm).keyID(signingKey.keyID).build(), jwtClaims)
        jwt.sign(RSASSASigner(signingKey.toPrivateKey()))
        return jwt.serialize()
    }

    private fun rejects(token: String, expected: String = nonce) {
        assertThrows(Exception::class.java) { verifier.verify(token, expected) }
    }

    @Test
    fun acceptsOnlyTrustedSignedIdentityWithoutEmailLinking() {
        val result = verifier.verify(signed(), nonce)
        assertEquals(VerifiedOidcIdentity(issuer, "provider-subject"), result)
    }

    @Test
    fun rejectsWrongSignatureAndUntrustedSigningKey() {
        rejects(signed(signingKey = RSAKeyGenerator(2048).keyID("trusted-key").generate()))
        rejects(signed(signingKey = RSAKeyGenerator(2048).keyID("unknown-key").generate()))
    }

    @Test
    fun rejectsWrongIssuerAudienceNonceAndMissingClaims() {
        rejects(signed(claims(tokenIssuer = "https://attacker.example/realm")))
        rejects(signed(claims(audience = listOf("other-client"))))
        rejects(signed(claims(audience = listOf(clientId, "other-client"))))
        rejects(signed(claims(tokenNonce = "wrong-nonce")))
        rejects(signed(claims(tokenNonce = null)))
        rejects(signed(claims(subject = "")))
        rejects(signed(), "z".repeat(43))
    }

    @Test
    fun enforcesOptionalNotBeforeWithThirtySecondSkew() {
        assertEquals(
            VerifiedOidcIdentity(issuer, "provider-subject"),
            verifier.verify(signed(claims(notBefore = Instant.now().plusSeconds(15))), nonce),
        )
        assertEquals(
            VerifiedOidcIdentity(issuer, "provider-subject"),
            verifier.verify(signed(claims(notBefore = Instant.now().minusSeconds(60))), nonce),
        )
        rejects(signed(claims(notBefore = Instant.now().plusSeconds(300))))
    }

    @Test
    fun validatesAuthorizedPartyWhenPresent() {
        assertEquals(
            VerifiedOidcIdentity(issuer, "provider-subject"),
            verifier.verify(signed(claims(authorizedParty = clientId)), nonce),
        )
        // An absent azp remains valid for the single-audience case.
        assertEquals(
            VerifiedOidcIdentity(issuer, "provider-subject"),
            verifier.verify(signed(claims()), nonce),
        )
        rejects(signed(claims(authorizedParty = "other-client")))
        rejects(signed(claims(authorizedParty = 42)))
    }

    @Test
    fun rejectsExpiredWrongAlgorithmAndUnsecuredOrMalformedTokens() {
        rejects(signed(claims(expiresAt = Instant.now().minusSeconds(600))))
        rejects(signed(algorithm = JWSAlgorithm.RS512))
        rejects(PlainJWT(claims()).serialize())
        rejects("not-a-jwt")
        rejects("")
        rejects(signed(), "short")
    }

    @Test
    fun rejectsEmptyTrustedKeySet() {
        assertThrows(IllegalArgumentException::class.java) {
            OidcIdTokenVerifier(
                OidcSettings(
                    URI(issuer),
                    URI("$issuer/protocol/openid-connect/auth"),
                    clientId,
                    URI("https://app.example/api/v1/auth/callback"),
                ),
                JWKSet(),
            )
        }
    }
}
