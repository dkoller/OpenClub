package org.openclub.api

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import java.net.URI
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** This policy may create cookies only from opaque 256-bit URL-safe random handles. */
internal class AuthCookiePolicy private constructor(private val production: Boolean) {
    val loginName: String = if (production) "__Host-openclub_login" else "openclub_dev_login"
    val sessionName: String = if (production) "__Host-openclub_session" else "openclub_dev_session"

    fun loginBinding(handle: String): Cookie = issued(loginName, handle, 5 * 60)
    fun session(handle: String): Cookie = issued(sessionName, handle, 12 * 60 * 60)
    fun clearLogin(): Cookie = cookie(loginName, "", 0)
    fun clearSession(): Cookie = cookie(sessionName, "", 0)

    private fun issued(name: String, value: String, duration: Int): Cookie {
        require(value.length == 43 && value.all { it.isLetterOrDigit() && it.isAscii() || it == '-' || it == '_' }) {
            "Expected an opaque base64url cookie handle"
        }
        return cookie(name, value, duration)
    }

    private fun Char.isAscii(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun cookie(name: String, value: String, maxAge: Int): Cookie = Cookie(
        name = name,
        value = value,
        encoding = CookieEncoding.RAW,
        path = "/",
        maxAge = maxAge,
        secure = production,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Lax"),
    )

    companion object {
        fun production(): AuthCookiePolicy = AuthCookiePolicy(true)
        fun localDevelopment(): AuthCookiePolicy = AuthCookiePolicy(false)
    }
}

/**
 * Explicit opt-in only. Disabled mode does not load credentials or register
 * auth routes. No secret-bearing data-class/toString or provider-specific fallback.
 */
internal class OidcRuntimeConfig private constructor(
    val settings: OidcSettings,
    val clientSecret: String,
    val publicOrigin: URI,
    val cookies: AuthCookiePolicy,
) {
    /**
     * Browsers serialize Origin with a lowercase host and omit default ports.
     * Normalize only trusted startup configuration; never normalize untrusted
     * request headers, which must match the resulting string exactly.
     */
    val browserOrigin: String
        get() {
            val host = publicOrigin.host.lowercase(java.util.Locale.ROOT)
            val authorityHost = if (host.startsWith("[") && host.endsWith("]")) {
                "[${canonicalIpv6(host.substring(1, host.length - 1))}]"
            } else host
            val port = publicOrigin.port.takeUnless {
                it == -1 || (publicOrigin.scheme == "https" && it == 443) ||
                    (publicOrigin.scheme == "http" && it == 80)
            }
            return "${publicOrigin.scheme}://$authorityHost" + (port?.let { ":$it" } ?: "")
        }

    /** WHATWG-style IPv6 spelling: lowercase hex, compress the first longest zero run. */
    private fun canonicalIpv6(literal: String): String {
        require('%' !in literal) { "IPv6 zone identifiers are not valid browser origins" }
        // Java represents IPv4-mapped IPv6 as Inet4Address. Restore the
        // 96-bit ::ffff: prefix rather than incorrectly rejecting a valid
        // bracketed IPv6 URL authority.
        val address = InetAddress.getByName(literal)
        val bytes = when (address) {
            is Inet6Address -> address.address
            is Inet4Address -> ByteArray(16).also { mapped ->
                mapped[10] = 0xff.toByte()
                mapped[11] = 0xff.toByte()
                address.address.copyInto(mapped, destinationOffset = 12)
            }
            else -> throw IllegalArgumentException("Expected an IPv6 address literal")
        }
        val groups = IntArray(8) { i ->
            (bytes[i * 2].toInt() and 0xff) * 256 + (bytes[i * 2 + 1].toInt() and 0xff)
        }
        var bestStart = -1
        var bestLength = 1
        var i = 0
        while (i < 8) {
            if (groups[i] != 0) {
                i++
                continue
            }
            val start = i
            while (i < 8 && groups[i] == 0) i++
            if (i - start > bestLength) {
                bestStart = start
                bestLength = i - start
            }
        }
        if (bestStart < 0) return groups.joinToString(":") { it.toString(16) }
        val left = (0 until bestStart).joinToString(":") { groups[it].toString(16) }
        val right = (bestStart + bestLength until 8).joinToString(":") { groups[it].toString(16) }
        return when {
            left.isEmpty() -> "::$right"
            right.isEmpty() -> "$left::"
            else -> "$left::$right"
        }
    }

    override fun toString(): String = "OidcRuntimeConfig(redacted)"

    companion object {
        fun fromEnvironment(env: Map<String, String>): OidcRuntimeConfig? {
            val mode = env["OPENCLUB_AUTH_MODE"] ?: "disabled"
            if (mode == "disabled") {
                require(env.keys.none { it.startsWith("OPENCLUB_OIDC_") || it == "OPENCLUB_PUBLIC_ORIGIN" }) {
                    "OIDC settings supplied while authentication is disabled"
                }
                return null
            }
            require(mode == "development" || mode == "production") { "Invalid OPENCLUB_AUTH_MODE" }

            fun required(name: String): String =
                requireNotNull(env[name]?.takeIf(String::isNotBlank)) { "$name is required" }

            fun uri(name: String): URI = try {
                URI(required(name))
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid $name URL")
            }

            val issuer = uri("OPENCLUB_OIDC_ISSUER")
            val authEndpoint = uri("OPENCLUB_OIDC_AUTHORIZATION_ENDPOINT")
            val redirectUri = uri("OPENCLUB_OIDC_REDIRECT_URI")
            val publicOrigin = uri("OPENCLUB_PUBLIC_ORIGIN")
            val secret = required("OPENCLUB_OIDC_CLIENT_SECRET")
            require(secret.length >= 32 && secret.none { it.isISOControl() }) {
                "OIDC client secret must be at least 32 characters"
            }
            val settings = OidcSettings(
                issuer, authEndpoint, required("OPENCLUB_OIDC_CLIENT_ID"), redirectUri,
            )

            require(publicOrigin.isAbsolute && publicOrigin.host != null &&
                publicOrigin.userInfo == null && publicOrigin.rawQuery == null &&
                publicOrigin.rawFragment == null && publicOrigin.path in listOf("", "/")) {
                "Public origin must be a full scheme/host/port origin without path"
            }
            require(publicOrigin.scheme == redirectUri.scheme &&
                publicOrigin.host.equals(redirectUri.host, ignoreCase = true) &&
                publicOrigin.port == redirectUri.port) {
                "OIDC callback must match the configured public origin"
            }
            val cookies = when (mode) {
                "production" -> {
                    require(issuer.scheme == "https" && publicOrigin.scheme == "https") {
                        "Production OIDC requires HTTPS issuer and callback"
                    }
                    AuthCookiePolicy.production()
                }
                else -> {
                    require(issuer.scheme == "http" && publicOrigin.scheme == "http" &&
                        issuer.host.isLoopback() && publicOrigin.host.isLoopback()) {
                        "Development OIDC requires explicit loopback-only HTTP"
                    }
                    AuthCookiePolicy.localDevelopment()
                }
            }
            return OidcRuntimeConfig(settings, secret, publicOrigin, cookies)
        }

        private fun String.isLoopback(): Boolean =
            equals("localhost", ignoreCase = true) || this == "127.0.0.1" || this == "[::1]"
    }
}
