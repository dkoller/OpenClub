package org.openclub.api.club

import kotlinx.coroutines.runBlocking
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID

/**
 * Restricted operator command. Approval, backup and independent identity checks
 * are procedural prerequisites; this CLI never trusts an email to grant access.
 */
fun main() {
    val env = System.getenv()
    require(env["OPENCLUB_RECOVERY_CONFIRM"] == "rebind-sole-admin") {
        "Explicit OPENCLUB_RECOVERY_CONFIRM=rebind-sole-admin required"
    }
    fun uuid(key: String): UUID =
        UUID.fromString(requireNotNull(env[key]?.takeIf(String::isNotBlank)) { "$key required" })

    val input = AdminRecovery.Input(
        clubId = uuid("OPENCLUB_RECOVERY_CLUB_ID"),
        adminMemberId = uuid("OPENCLUB_RECOVERY_MEMBER_ID"),
        oldAccountId = uuid("OPENCLUB_RECOVERY_OLD_ACCOUNT_ID"),
        replacementAccountId = uuid("OPENCLUB_RECOVERY_NEW_ACCOUNT_ID"),
    )
    requireNotNull(DatabaseRuntime.openIfConfigured(env)) { "DB_URL required" }.use { database ->
        runBlocking { database.onDatabaseThread { AdminRecovery(it).rebind(input) } }
    }
    println("Sole administrator account rebound; previous account sessions revoked")
}
