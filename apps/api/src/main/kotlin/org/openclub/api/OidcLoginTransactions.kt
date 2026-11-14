package org.openclub.api

import org.jooq.DSLContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * A pre-authentication browser binding, not a logged-in session.
 * Only a later, trusted Ktor route may place [browserBinding] in an HttpOnly cookie.
 */
internal data class PendingOidcLogin(
    val authorization: OidcAuthorizationRequest,
    val browserBinding: String,
)

internal data class ConsumedOidcLogin(val nonce: String, val codeVerifier: String)

/**
 * Blocking JDBC: callers must use an I/O dispatcher. Persist before any login redirect.
 * Callback must additionally verify the OIDC response and must not issue a session here.
 */
internal class OidcLoginTransactions(
    private val database: DSLContext,
    private val requests: OidcAuthorizationRequests,
    private val random: SecureRandom = SecureRandom(),
) {
    fun start(): PendingOidcLogin {
        val authorization = requests.create()
        val binding = ByteArray(32).also(random::nextBytes)
        val browserBinding = Base64.getUrlEncoder().withoutPadding().encodeToString(binding)
        database.execute(
            """INSERT INTO oidc_login_transactions
                (state_hash, browser_hash, nonce, code_verifier, expires_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes')""",
            digest(authorization.state), digest(browserBinding),
            authorization.nonce, authorization.codeVerifier,
        )
        return PendingOidcLogin(authorization, browserBinding)
    }

    /**
     * Atomic, one-time consumption. Even a wrong browser binding burns the state;
     * this fails closed and prevents repeated attempts with the same state.
     */
    fun consume(state: String?, browserBinding: String?): ConsumedOidcLogin? {
        if (!isExpectedSecret(state) || !isExpectedSecret(browserBinding)) return null
        val record = database.fetchOne(
            """DELETE FROM oidc_login_transactions
                WHERE state_hash = ?
                RETURNING browser_hash, nonce, code_verifier, expires_at > CURRENT_TIMESTAMP AS valid""",
            digest(state!!),
        ) ?: return null
        if (record.get("valid", Boolean::class.java) != true ||
            !MessageDigest.isEqual(
                record.get("browser_hash", String::class.java).trim().toByteArray(StandardCharsets.US_ASCII),
                digest(browserBinding!!).toByteArray(StandardCharsets.US_ASCII),
            )
        ) return null
        return ConsumedOidcLogin(
            record.get("nonce", String::class.java),
            record.get("code_verifier", String::class.java),
        )
    }

    fun pruneExpired(): Int =
        database.execute("DELETE FROM oidc_login_transactions WHERE expires_at <= CURRENT_TIMESTAMP")

    private fun isExpectedSecret(value: String?): Boolean =
        value != null && value.length == 43 && value.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        }

    private fun digest(value: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.US_ASCII))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
