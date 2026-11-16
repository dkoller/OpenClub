package org.openclub.api.auth

import java.net.URI

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
                "[${BrowserOrigin.canonicalIpv6(host.substring(1, host.length - 1))}]"
            } else host
            val port = publicOrigin.port.takeUnless {
                it == -1 || (publicOrigin.scheme == "https" && it == 443) ||
                    (publicOrigin.scheme == "http" && it == 80)
            }
            return "${publicOrigin.scheme}://$authorityHost" + (port?.let { ":$it" } ?: "")
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
                    require(issuer.host.isLoopback() && publicOrigin.host.isLoopback() &&
                        ((issuer.scheme == "http" && publicOrigin.scheme == "http") ||
                            (issuer.scheme == "https" && publicOrigin.scheme == "https"))) {
                        "Development OIDC requires loopback-only matching HTTP or HTTPS"
                    }
                    // HTTPS development is required for passkey-capable providers
                    // such as Rauthy. Never issue insecure cookies on HTTPS.
                    if (publicOrigin.scheme == "https") AuthCookiePolicy.production()
                    else AuthCookiePolicy.localDevelopment()
                }
            }
            return OidcRuntimeConfig(settings, secret, publicOrigin, cookies)
        }

        private fun String.isLoopback(): Boolean =
            equals("localhost", ignoreCase = true) || this == "127.0.0.1" || this == "[::1]"
    }
}
