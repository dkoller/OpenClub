package org.openclub.api.members

import kotlinx.coroutines.runBlocking
import org.openclub.api.club.canonicalUuid
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID

/**
 * Restricted operator command: print the JSON export of one member to standard output. Redirect it to a
 * private file created with a restrictive umask and outside the repository; it contains personal data and must
 * be delivered to the verified requester out of band and then disposed of under the controller's policy.
 * Messages go to standard error so the redirected file holds only the JSON.
 */
fun main() {
    val env = System.getenv()
    val memberId = exportInput(env)
    requireNotNull(DatabaseRuntime.openIfConfigured(env)) { "DB_URL required" }.use { database ->
        val export = runBlocking { database.onDatabaseThread { MemberExport(it).exportAsOperator(memberId) } }
        checkNotNull(export) { "No member with that ID; nothing exported" }
        println(export.toString())
        System.err.println("Member exported; an audit event was recorded. The output contains personal data.")
    }
}

/** Validated before any database connection: the confirmation phrase and a canonical member UUID. */
internal fun exportInput(env: Map<String, String>): UUID {
    require(env["OPENCLUB_EXPORT_CONFIRM"] == "export-member") {
        "Explicit OPENCLUB_EXPORT_CONFIRM=export-member required"
    }
    return canonicalUuid(requireNotNull(env["OPENCLUB_EXPORT_MEMBER_ID"]?.takeIf(String::isNotBlank)) {
        "OPENCLUB_EXPORT_MEMBER_ID required"
    }, "OPENCLUB_EXPORT_MEMBER_ID")
}
