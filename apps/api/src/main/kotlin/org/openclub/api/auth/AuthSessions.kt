package org.openclub.api.auth

import org.openclub.api.database.*
import org.openclub.api.members.*

import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Handle belongs exclusively in an HttpOnly cookie; CSRF token is returned by /auth/me. */
internal data class IssuedSession(val handle: String, val csrfToken: String)

/** Authentication proves a person, not membership or a club-specific role. */
internal data class ActiveSession(val accountId: UUID, val csrfToken: String)

internal enum class LogoutResult { REVOKED, UNAUTHENTICATED, FORBIDDEN }

/**
 * Internal PostgreSQL-backed opaque sessions. [accountId] must already be resolved
 * from a VERIFIED issuer+subject identity, never directly from request data.
 *
 * Every call is blocking JDBC; run off Ktor event loops on a bounded I/O dispatcher.
 * No HTTP auth routes or cookies are enabled by this class.
 */
internal class AuthSessions(
    private val database: DSLContext,
    private val random: SecureRandom = SecureRandom(),
) {
    fun create(accountId: UUID): IssuedSession {
        val issued = newSecrets()
        database.execute(
            """INSERT INTO auth_sessions
               (session_hash, account_id, csrf_token, idle_expires_at, absolute_expires_at)
               VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '30 minutes',
                       CURRENT_TIMESTAMP + INTERVAL '12 hours')""",
            digest(issued.handle), accountId, issued.csrfToken,
        )
        return issued
    }

    /**
     * Re-login is all-or-nothing: an insert failure must not revoke a valid
     * existing browser session. Use the transaction-scoped DSLContext for both
     * statements, not this class's outer context.
     */
    fun replace(previousHandle: String?, accountId: UUID): IssuedSession =
        database.transactionResult { configuration ->
            val sessions = AuthSessions(DSL.using(configuration), random)
            if (previousHandle != null) sessions.revoke(previousHandle)
            sessions.create(accountId)
        }

    /** Refresh idle expiry only for valid, unexpired sessions. */
    fun find(handle: String?): ActiveSession? = touch(handle, null)

    /** Lock the session so rotation/re-login cannot race CSRF validation and deletion. */
    fun logout(handle: String?, csrfToken: String?): LogoutResult {
        if (!validSecret(handle)) return LogoutResult.UNAUTHENTICATED
        return database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            val row = db.fetchOne(
                """SELECT csrf_token FROM auth_sessions WHERE session_hash = ?
                   AND idle_expires_at > CURRENT_TIMESTAMP
                   AND absolute_expires_at > CURRENT_TIMESTAMP FOR UPDATE""",
                digest(handle!!),
            )
            if (row == null) LogoutResult.UNAUTHENTICATED
            else if (!validSecret(csrfToken) || !MessageDigest.isEqual(
                    row.get("csrf_token", String::class.java)!!.toByteArray(StandardCharsets.US_ASCII),
                    csrfToken!!.toByteArray(StandardCharsets.US_ASCII),
                )) LogoutResult.FORBIDDEN
            else {
                db.execute("DELETE FROM auth_sessions WHERE session_hash = ?", digest(handle))
                LogoutResult.REVOKED
            }
        }
    }

    /**
     * For a future state-changing route, also check the synchronizer token.
     * Invalid CSRF must not refresh idle expiry. An allowlisted Origin check
     * remains mandatory in the HTTP handler (not implemented here).
     */
    fun findForMutation(handle: String?, csrfToken: String?): ActiveSession? {
        if (!validSecret(csrfToken)) return null
        return touch(handle, csrfToken)
    }

    private fun touch(handle: String?, csrfToken: String?): ActiveSession? {
        if (!validSecret(handle)) return null
        val csrfCondition = if (csrfToken != null) "AND csrf_token = ?" else ""
        val statement = """UPDATE auth_sessions
            SET last_used_at = CURRENT_TIMESTAMP,
                idle_expires_at = LEAST(absolute_expires_at, CURRENT_TIMESTAMP + INTERVAL '30 minutes')
            WHERE session_hash = ?
              AND idle_expires_at > CURRENT_TIMESTAMP
              AND absolute_expires_at > CURRENT_TIMESTAMP
              $csrfCondition
            RETURNING account_id, csrf_token"""
        val row = if (csrfToken != null) {
            database.fetchOne(statement, digest(handle!!), csrfToken)
        } else {
            database.fetchOne(statement, digest(handle!!))
        } ?: return null
        return ActiveSession(
            requireNotNull(row.get("account_id", UUID::class.java)),
            requireNotNull(row.get("csrf_token", String::class.java)),
        )
    }

    /** Rotate both handle and CSRF token atomically, preserving the 12-hour hard expiry. */
    fun rotate(handle: String?): IssuedSession? {
        if (!validSecret(handle)) return null
        val replacement = newSecrets()
        val changed = database.execute(
            """UPDATE auth_sessions
               SET session_hash = ?, csrf_token = ?,
                   last_used_at = CURRENT_TIMESTAMP,
                   idle_expires_at = LEAST(absolute_expires_at, CURRENT_TIMESTAMP + INTERVAL '30 minutes')
               WHERE session_hash = ?
                 AND idle_expires_at > CURRENT_TIMESTAMP
                 AND absolute_expires_at > CURRENT_TIMESTAMP""",
            digest(replacement.handle), replacement.csrfToken, digest(handle!!),
        )
        return replacement.takeIf { changed == 1 }
    }

    /** Logout is idempotent; an invalid or already revoked handle has no effect. */
    fun revoke(handle: String?): Boolean =
        validSecret(handle) &&
            database.execute("DELETE FROM auth_sessions WHERE session_hash = ?", digest(handle!!)) == 1

    /** Invoke only from trusted account/identity lifecycle logic, not raw request IDs. */
    fun revokeAllForAccount(accountId: UUID): Int =
        database.execute("DELETE FROM auth_sessions WHERE account_id = ?", accountId)

    fun pruneExpired(): Int =
        database.execute(
            "DELETE FROM auth_sessions WHERE idle_expires_at <= CURRENT_TIMESTAMP OR absolute_expires_at <= CURRENT_TIMESTAMP"
        )

    private fun newSecrets(): IssuedSession = IssuedSession(secret(), secret())

    private fun secret(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun validSecret(value: String?): Boolean =
        value != null && value.length == 43 && value.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        }

    private fun digest(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
}
