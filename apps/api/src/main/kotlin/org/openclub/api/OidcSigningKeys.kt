package org.openclub.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import java.time.Duration

/** Shared per issuer runtime; call only on the bounded token-exchange dispatcher. */
internal class OidcSigningKeys(
    initial: JWKSet,
    private val reload: (() -> JWKSet)?,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var keys = initial
    private var loadedAt = nanoTime()
    private var attemptedAt: Long? = null
    private val lifetime = Duration.ofMinutes(5).toNanos()
    private val cooldown = Duration.ofSeconds(30).toNanos()

    // Serialize refresh and publication so parallel callbacks cannot stampede the provider.
    @Synchronized
    fun forToken(compact: String): JWKSet {
        require(compact.length in 1..16_384) { "Invalid ID token length" }
        val header = SignedJWT.parse(compact).header
        require(header.algorithm == JWSAlgorithm.RS256 && !header.keyID.isNullOrBlank()) {
            "Invalid signing key header"
        }
        val now = nanoTime()
        val expired = now - loadedAt >= lifetime
        val missing = keys.getKeyByKeyId(header.keyID) == null
        if (expired || missing) {
            val refresh = requireNotNull(reload) { "Signing key refresh unavailable" }
            val previous = attemptedAt
            require(previous == null || now - previous >= cooldown) { "Signing key refresh cooling down" }
            attemptedAt = now
            val replacement = refresh()
            require(replacement.keys.isNotEmpty()) { "Empty signing keys" }
            keys = replacement
            loadedAt = nanoTime()
        }
        require(keys.getKeyByKeyId(header.keyID) != null) { "Unknown signing key" }
        return keys
    }
}
