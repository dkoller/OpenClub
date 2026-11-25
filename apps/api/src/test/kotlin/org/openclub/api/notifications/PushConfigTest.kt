package org.openclub.api.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64

/** Database-free checks of the push settings, the endpoint rule and the VAPID header (OpenAPI 1.33.0). */
class PushConfigTest {
    private fun keys(): Pair<String, String> {
        val out = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(out))
        try { main() } finally { System.setOut(previous) }
        val lines = out.toString().lines().filter { it.isNotBlank() }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return lines.getValue("OPENCLUB_PUSH_PUBLIC_KEY") to lines.getValue("OPENCLUB_PUSH_PRIVATE_KEY")
    }

    private fun env(public: String, private: String) = mapOf(
        "OPENCLUB_PUSH_PUBLIC_KEY" to public, "OPENCLUB_PUSH_PRIVATE_KEY" to private,
        "OPENCLUB_PUSH_SUBJECT" to "mailto:club@example.invalid",
        "OPENCLUB_PUSH_HOSTS" to "fcm.googleapis.com, .push.apple.com")

    @Test
    fun offWithoutKeysAndStrictWhenConfigured() {
        assertNull(PushConfig.fromEnvironment(emptyMap()))
        val (public, private) = keys()
        val (otherPublic, _) = keys()
        assertTrue(PushConfig.fromEnvironment(env(public, private)) != null)
        assertThrows(IllegalArgumentException::class.java) { PushConfig.fromEnvironment(env(otherPublic, private)) }
        assertThrows(IllegalArgumentException::class.java) { PushConfig.fromEnvironment(env(public, "short")) }
        assertThrows(IllegalArgumentException::class.java) {
            PushConfig.fromEnvironment(env(public, private) + ("OPENCLUB_PUSH_SUBJECT" to "club@example.invalid"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushConfig.fromEnvironment(env(public, private) + ("OPENCLUB_PUSH_HOSTS" to ""))
        }
    }

    @Test
    fun onlyHttpsEndpointsOnTheConfiguredHosts() {
        val (public, private) = keys()
        val config = requireNotNull(PushConfig.fromEnvironment(env(public, private)))
        assertTrue(config.allows("https://fcm.googleapis.com/fcm/send/abc"))
        assertTrue(config.allows("https://web.push.apple.com/QOabc"))
        assertFalse(config.allows("https://push.apple.com/abc"), "a suffix needs a subdomain")
        assertFalse(config.allows("http://fcm.googleapis.com/fcm/send/abc"))
        assertFalse(config.allows("https://fcm.googleapis.com:8443/x"))
        assertFalse(config.allows("https://user@fcm.googleapis.com/x"))
        assertFalse(config.allows("https://fcm.googleapis.com.evil.example/x"))
        assertFalse(config.allows("https://169.254.169.254/latest"))
        assertFalse(config.allows("https://fcm.googleapis.com/" + "x".repeat(1000)))
    }

    @Test
    fun theVapidTokenIsSignedForTheEndpointsOrigin() {
        val (public, private) = keys()
        val config = requireNotNull(PushConfig.fromEnvironment(env(public, private)))
        val header = config.authorization("https://fcm.googleapis.com/fcm/send/abc", Instant.ofEpochSecond(1_800_000_000))
        val token = header.substringAfter("vapid t=").substringBefore(", k=")
        assertEquals(public, header.substringAfter(", k="))
        val (head, claims, signature) = token.split('.')
        val decoder = Base64.getUrlDecoder()
        val body = String(decoder.decode(claims))
        assertTrue(""""aud":"https://fcm.googleapis.com"""" in body && """"exp":1800043200""" in body, body)
        // Verify r‖s with the public key, converted back to DER.
        val raw = decoder.decode(signature)
        assertEquals(64, raw.size)
        fun der(part: ByteArray): ByteArray { val v = BigInteger(1, part).toByteArray(); return byteArrayOf(2, v.size.toByte()) + v }
        val sequence = der(raw.copyOfRange(0, 32)) + der(raw.copyOfRange(32, 64))
        val derSignature = byteArrayOf(0x30, sequence.size.toByte()) + sequence
        val point = decoder.decode(public)
        val params = java.security.AlgorithmParameters.getInstance("EC").apply { init(java.security.spec.ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))), params))
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update("$head.$claims".toByteArray()) }.verify(derSignature))
    }
}
