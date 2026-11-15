package org.openclub.api.club

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.database.AdvisoryLocks
import java.util.UUID

/**
 * Offline operator-only re-binding of the sole administrator member after
 * loss of its login. No HTTP path, member creation or role promotion.
 */
internal class AdminRecovery(private val database: DSLContext) {
    data class Input(
        val clubId: UUID,
        val adminMemberId: UUID,
        val oldAccountId: UUID,
        val replacementAccountId: UUID,
    )

    fun rebind(input: Input) {
        require(input.oldAccountId != input.replacementAccountId) {
            "Replacement must be a different account"
        }
        database.transaction { configuration ->
            val db = DSL.using(configuration)
            // Same application-level lock as initial bootstrap; serializes recoveries.
            db.fetchValue(AdvisoryLocks.BOOTSTRAP)
            check(db.fetchOne("SELECT 1 FROM clubs WHERE id = ?", input.clubId) != null) {
                "Installation club does not match"
            }
            check(db.fetchOne(
                """SELECT 1 FROM members
                   WHERE id = ? AND account_id = ? AND role = 'ADMIN' FOR UPDATE""",
                input.adminMemberId, input.oldAccountId,
            ) != null) { "Administrator member and previous account do not match" }
            // Recovery must not be usable as an alternative to normal admin management.
            check(db.fetchOne("SELECT COUNT(*) AS total FROM members WHERE role = 'ADMIN'")
                ?.get("total", Int::class.java) == 1) {
                "Recovery is limited to installations with exactly one administrator"
            }
            check(db.fetchOne(
                "SELECT 1 FROM account_identities WHERE account_id = ? LIMIT 1",
                input.replacementAccountId,
            ) != null) { "Replacement account must have a verified OIDC identity" }
            check(db.fetchOne(
                "SELECT 1 FROM members WHERE account_id = ? LIMIT 1",
                input.replacementAccountId,
            ) == null) { "Replacement account is already linked to a member" }
            check(db.execute(
                """UPDATE members SET account_id = ?
                   WHERE id = ? AND account_id = ? AND role = 'ADMIN'""",
                input.replacementAccountId, input.adminMemberId, input.oldAccountId,
            ) == 1) { "Administrator link changed during recovery" }
            // Revoke existing sessions on both accounts. A replacement account may
            // already be signed in as an unlinked user and must reauthenticate
            // after receiving administrator privileges.
            db.execute("DELETE FROM auth_sessions WHERE account_id IN (?, ?)",
                input.oldAccountId, input.replacementAccountId)
        }
    }
}
