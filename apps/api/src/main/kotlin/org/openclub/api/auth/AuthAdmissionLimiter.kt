package org.openclub.api.auth

import org.openclub.api.database.*
import org.openclub.api.members.*

import java.time.Duration

/**
 * Process-local, fixed-memory admission budget for unauthenticated OIDC routes.
 * This protects bounded workers/DB from unlimited requests, not from a distributed
 * denial of service. No untrusted proxy headers or client-provided keys are used.
 */
internal class AuthAdmissionLimiter(
    private val loginLimit: Int = 60,
    private val callbackLimit: Int = 120,
    private val invitationLimit: Int = 60,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(loginLimit > 0 && callbackLimit > 0 && invitationLimit > 0)
    }

    private val window = Duration.ofMinutes(1).toNanos()
    private var started = nanoTime()
    private var logins = 0
    private var callbacks = 0
    private var invitations = 0

    @Synchronized
    fun allowLogin(): Boolean {
        resetIfElapsed()
        if (logins >= loginLimit) return false
        logins++
        return true
    }

    @Synchronized
    fun allowCallback(): Boolean {
        resetIfElapsed()
        if (callbacks >= callbackLimit) return false
        callbacks++
        return true
    }

    /** Invitation acceptance: bounds guessing of invitation secrets; shared budget, never per client. */
    @Synchronized
    fun allowInvitation(): Boolean {
        resetIfElapsed()
        if (invitations >= invitationLimit) return false
        invitations++
        return true
    }

    private fun resetIfElapsed() {
        val now = nanoTime()
        if (now - started >= window) {
            started = now
            logins = 0
            callbacks = 0
            invitations = 0
        }
    }
}
