package org.openclub.api.auth

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding

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
