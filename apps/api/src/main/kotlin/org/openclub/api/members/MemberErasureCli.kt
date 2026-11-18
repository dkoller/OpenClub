package org.openclub.api.members

import kotlinx.coroutines.runBlocking
import org.openclub.api.club.canonicalUuid
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID

/**
 * Restricted operator command: permanently erase one archived member after a verified request. Identity
 * verification of the requester and the controller's decision are procedural prerequisites (see the
 * single-club operations guide); the typed full name guards against erasing the wrong record.
 */
fun main() {
    val env = System.getenv()
    val input = erasureInput(env)
    requireNotNull(DatabaseRuntime.openIfConfigured(env)) { "DB_URL required" }.use { database ->
        val result = runBlocking { database.onDatabaseThread { MemberErasure(it).eraseAsOperator(input.memberId, input.name) } }
        check(result == MemberErasure.Result.ERASED) {
            "Not erased: no archived member with that ID and name. Archive the member first and check the typed name."
        }
    }
    println("Archived member erased; an audit event was recorded")
}

internal data class ErasureInput(val memberId: UUID, val name: String)

/** Validated before any database connection: the confirmation phrase, a canonical member UUID and the typed name. */
internal fun erasureInput(env: Map<String, String>): ErasureInput {
    require(env["OPENCLUB_ERASE_CONFIRM"] == "erase-archived-member") {
        "Explicit OPENCLUB_ERASE_CONFIRM=erase-archived-member required"
    }
    val id = canonicalUuid(requireNotNull(env["OPENCLUB_ERASE_MEMBER_ID"]?.takeIf(String::isNotBlank)) {
        "OPENCLUB_ERASE_MEMBER_ID required"
    }, "OPENCLUB_ERASE_MEMBER_ID")
    val name = requireNotNull(env["OPENCLUB_ERASE_NAME"]?.takeIf(String::isNotBlank)) {
        "OPENCLUB_ERASE_NAME (the member's full name) required"
    }.trim()
    return ErasureInput(id, name)
}
