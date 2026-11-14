package org.openclub.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Callable

class OidcSigningKeysTest {
    private val first = RSAKeyGenerator(2048).keyID("first").generate()
    private val second = RSAKeyGenerator(2048).keyID("second").generate()
    private fun token(key: com.nimbusds.jose.jwk.RSAKey): String =
        SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), JWTClaimsSet.Builder().build())
            .apply { sign(RSASSASigner(key)) }.serialize()

    @Test
    fun unknownKeyRefreshesOnceAndRemovedKeysAreRejected() {
        var calls = 0
        val cache = OidcSigningKeys(JWKSet(first.toPublicJWK()), {
            calls++
            JWKSet(second.toPublicJWK())
        })
        assertNotNull(cache.forToken(token(first)).getKeyByKeyId("first"))
        assertNotNull(cache.forToken(token(second)).getKeyByKeyId("second"))
        assertThrows(Exception::class.java) { cache.forToken(token(first)) }
        assertEquals(1, calls)
    }

    @Test
    fun expiryFailsClosedAndFailureHasSharedCooldown() {
        var now = 0L
        var calls = 0
        val cache = OidcSigningKeys(JWKSet(first.toPublicJWK()), {
            calls++
            error("provider unavailable")
        }, { now })
        now = Duration.ofMinutes(5).toNanos()
        repeat(2) { assertThrows(Exception::class.java) { cache.forToken(token(first)) } }
        assertEquals(1, calls)
        now += Duration.ofSeconds(30).toNanos()
        assertThrows(Exception::class.java) { cache.forToken(token(first)) }
        assertEquals(2, calls)
    }

    @Test
    fun freshKnownKeySurvivesFailedUnknownKeyRefresh() {
        var now = 0L
        var calls = 0
        val cache = OidcSigningKeys(JWKSet(first.toPublicJWK()), {
            calls++
            error("provider unavailable")
        }, { now })
        val known = token(first)
        val unknown = token(second)

        assertThrows(Exception::class.java) { cache.forToken(unknown) }
        assertNotNull(cache.forToken(known).getKeyByKeyId("first"))
        assertThrows(Exception::class.java) { cache.forToken(unknown) }
        assertEquals(1, calls)

        now = Duration.ofSeconds(30).toNanos()
        assertThrows(Exception::class.java) { cache.forToken(unknown) }
        assertEquals(2, calls)
        assertNotNull(cache.forToken(known).getKeyByKeyId("first"))
    }

    @Test
    fun simultaneousRotationUsesOneRefresh() {
        var calls = 0
        val cache = OidcSigningKeys(JWKSet(first.toPublicJWK()), {
            calls++
            JWKSet(second.toPublicJWK())
        })
        val compact = token(second)
        val pool = Executors.newFixedThreadPool(8)
        try {
            pool.invokeAll((1..16).map { Callable { cache.forToken(compact) } }).forEach {
                assertNotNull(it.get().getKeyByKeyId("second"))
            }
        } finally { pool.shutdownNow() }
        assertEquals(1, calls)
    }

    @Test
    fun successfulExpiryRefreshRemovesOldKeys() {
        var now = 0L
        val cache = OidcSigningKeys(JWKSet(first.toPublicJWK()), { JWKSet(second.toPublicJWK()) }, { now })
        now = Duration.ofMinutes(5).toNanos()
        assertThrows(Exception::class.java) { cache.forToken(token(first)) }
        assertNotNull(cache.forToken(token(second)).getKeyByKeyId("second"))
    }
}
