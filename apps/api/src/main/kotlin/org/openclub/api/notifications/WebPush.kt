package org.openclub.api.notifications

import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Push settings (OpenAPI 1.33.0, ADR-0008 addendum 37), from the environment the operator's deployment config writes.
 * Without `OPENCLUB_PUSH_PUBLIC_KEY` push is off.
 *
 * - `OPENCLUB_PUSH_PUBLIC_KEY` and `OPENCLUB_PUSH_PRIVATE_KEY`: the VAPID key pair (P-256), base64url without padding:
 *   the uncompressed public point (65 bytes) and the private scalar (32 bytes). They must belong together.
 * - `OPENCLUB_PUSH_SUBJECT`: a `mailto:` or `https:` contact for the push services.
 * - `OPENCLUB_PUSH_HOSTS`: comma-separated host names of the push services the server may call (exact names or
 *   `.suffix`), for example `fcm.googleapis.com,updates.push.services.mozilla.com,.push.apple.com,.notify.windows.com`.
 *   Endpoints on any other host are refused, so the server never calls an address a browser made up.
 */
internal data class PushConfig(
    val publicKey: String, private val privateKey: ECPrivateKey, val subject: String, val hosts: List<String>,
) {
    override fun toString() = "PushConfig(subject=$subject, hosts=$hosts)"

    /** True for an `https` URL without user info or port other than 443 on an allowed host. */
    fun allows(endpoint: String): Boolean {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.scheme != "https" || uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return false
        if (endpoint.length > 1000) return false
        return hosts.any { allowed -> if (allowed.startsWith(".")) host.endsWith(allowed) && host.length > allowed.length else host == allowed }
    }

    /** The `Authorization` header value for [endpoint]: an ES256 JWT for its origin, valid for 12 hours, and the key. */
    fun authorization(endpoint: String, now: Instant = Instant.now()): String {
        val uri = URI(endpoint)
        val audience = "https://${uri.host}"
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = encoder.encodeToString(
            """{"aud":"$audience","exp":${now.plus(Duration.ofHours(12)).epochSecond},"sub":"$subject"}""".toByteArray())
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey); update("$header.$claims".toByteArray()) }
        return "vapid t=$header.$claims.${encoder.encodeToString(derToRaw(signer.sign()))}, k=$publicKey"
    }

    companion object {
        private val HOST = Regex("^\\.?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

        /** Null when push is not configured; an exception for a partial or invalid configuration (fail at startup). */
        fun fromEnvironment(env: Map<String, String>): PushConfig? {
            val public = env["OPENCLUB_PUSH_PUBLIC_KEY"]?.trim().orEmpty()
            if (public.isEmpty()) return null
            val private = env["OPENCLUB_PUSH_PRIVATE_KEY"]?.trim().orEmpty()
            val subject = env["OPENCLUB_PUSH_SUBJECT"]?.trim().orEmpty()
            require(subject.startsWith("mailto:") || subject.startsWith("https://")) { "OPENCLUB_PUSH_SUBJECT must be mailto: or https:" }
            require(subject.none { it == '"' || it == '\\' || Character.isISOControl(it) }) { "OPENCLUB_PUSH_SUBJECT is invalid" }
            val hosts = env["OPENCLUB_PUSH_HOSTS"].orEmpty().split(',').map { it.trim().lowercase() }.filter(String::isNotEmpty)
            require(hosts.isNotEmpty() && hosts.all(HOST::matches)) { "OPENCLUB_PUSH_HOSTS must list push service host names" }
            val decoder = Base64.getUrlDecoder()
            val point = runCatching { decoder.decode(public) }.getOrNull()
            val scalar = runCatching { decoder.decode(private) }.getOrNull()
            require(point != null && point.size == 65 && point[0] == 4.toByte()) { "OPENCLUB_PUSH_PUBLIC_KEY must be a P-256 public point" }
            require(scalar != null && scalar.size == 32) { "OPENCLUB_PUSH_PRIVATE_KEY must be a P-256 private key" }
            val params = curve()
            val factory = KeyFactory.getInstance("EC")
            val key = factory.generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params)) as ECPrivateKey
            val publicKey = factory.generatePublic(ECPublicKeySpec(
                ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))), params)) as ECPublicKey
            // The pair must match: sign and verify a probe.
            val probe = "openclub".toByteArray()
            val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(probe) }.sign()
            require(Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(probe) }.verify(signature)) {
                "OPENCLUB_PUSH_PUBLIC_KEY and OPENCLUB_PUSH_PRIVATE_KEY do not belong together"
            }
            return PushConfig(public, key, subject, hosts)
        }

        private fun curve(): ECParameterSpec = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)

        /** A DER ECDSA signature as the 64-byte r‖s that JWS ES256 uses. */
        fun derToRaw(der: ByteArray): ByteArray {
            var index = 2 + (if (der[1].toInt() and 0x80 != 0) der[1].toInt() and 0x7f else 0)
            fun integer(): ByteArray {
                require(der[index] == 0x02.toByte())
                val length = der[index + 1].toInt()
                val value = der.copyOfRange(index + 2, index + 2 + length)
                index += 2 + length
                val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
                require(trimmed.size <= 32)
                return ByteArray(32 - trimmed.size) + trimmed
            }
            return integer() + integer()
        }
    }
}

/** Sends one empty push; answers the HTTP status (or 0 when the service could not be reached). */
internal fun interface PushSender {
    fun send(endpoint: String): Int
}

internal class HttpPushSender(private val config: PushConfig) : PushSender {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    override fun send(endpoint: String): Int {
        if (!config.allows(endpoint)) return 410
        val request = HttpRequest.newBuilder(URI(endpoint)).timeout(Duration.ofSeconds(15))
            .header("Authorization", config.authorization(endpoint))
            .header("TTL", "86400").header("Urgency", "normal")
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        return try {
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (_: java.io.IOException) {
            0
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            0
        }
    }
}
