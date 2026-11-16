package org.openclub.api.members

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Opaque, authenticated, per-process page token. Restart invalidates previous pages. */
internal object MemberPageCursor {
    private val random = SecureRandom()
    private val key = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(actor: UUID, query: String, after: MemberDirectory.After): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(scope(actor, query))
        // Embedded controls are forbidden in stored names.
        val value = listOf(after.lastName, after.firstName, after.id.toString()).joinToString("\u0000")
        return encoder.encodeToString(nonce + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decode(actor: UUID, query: String, cursor: String): MemberDirectory.After {
        require(cursor.length in 1..2048) { "Invalid page cursor" }
        try {
            val bytes = decoder.decode(cursor)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(scope(actor, query))
            val parts = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)),
                StandardCharsets.UTF_8).split("\u0000")
            require(parts.size == 3 && parts[0].isNotBlank() && parts[1].isNotBlank())
            return MemberDirectory.After(parts[0], parts[1], UUID.fromString(parts[2]))
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid page cursor")
        }
    }

    private fun scope(actor: UUID, query: String) =
        (actor.toString() + "\u0000" + query).toByteArray(StandardCharsets.UTF_8)
}
