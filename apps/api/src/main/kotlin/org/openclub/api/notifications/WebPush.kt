package org.openclub.api.notifications

import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
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

    /**
     * True for an `https` URL without user info or port other than 443 on an allowed host, written in lower case (as
     * browsers do), so the host checked here, the one called and the token's audience are the same string.
     */
    fun allows(endpoint: String): Boolean {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
        val host = uri.host?.takeIf { it == it.lowercase() } ?: return false
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
            // A suffix must name a push service, not a whole provider or country domain (".googleapis.com", ".co.uk").
            require(hosts.filter { it.startsWith(".") }.all { it.count { c -> c == '.' } >= 3 }) {
                "OPENCLUB_PUSH_HOSTS suffixes need at least three labels, for example .push.apple.com"
            }
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

/**
 * Calls the push service. Besides the host list, every address the host resolves to must be public (security review
 * 2026-11-26): a name under an allowed suffix that resolves to a private, loopback or link-local address is not called.
 */
internal class HttpPushSender(
    private val config: PushConfig,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : PushSender {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).proxy(java.net.ProxySelector.of(null)).build()

    override fun send(endpoint: String): Int {
        if (!config.allows(endpoint)) return 410
        val addresses = try {
            resolve(URI(endpoint).host)
        } catch (_: java.net.UnknownHostException) {
            return 0
        }
        if (addresses.isEmpty() || !addresses.all(::isPublic)) return 0
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

/** False for loopback, private, link-local, carrier-grade NAT, unique-local, multicast, reserved and unspecified addresses. */
internal fun isPublic(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
        address.isMulticastAddress) return false
    val b = address.address.map { it.toInt() and 0xff }
    return if (address is Inet4Address) {
        !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
            (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) || (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && b[1] == 168) || (b[0] == 192 && b[1] == 0 && b[2] == 0) || (b[0] == 198 && b[1] in 18..19))
    } else {
        // fc00::/7 unique local, fec0::/10 site local, and IPv4-compatible/mapped or NAT64 forms are refused.
        !((b[0] and 0xfe) == 0xfc || (b[0] == 0xfe && (b[1] and 0xc0) == 0xc0) || b.take(10).all { it == 0 } ||
            (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b) || (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8))
    }
}
