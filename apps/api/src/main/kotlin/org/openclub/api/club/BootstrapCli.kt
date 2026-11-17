package org.openclub.api.club

import kotlinx.coroutines.runBlocking
import org.openclub.api.database.DatabaseRuntime
import java.time.LocalDate
import java.util.UUID

/**
 * Run only from an operator-controlled shell, never from the web application.
 * Sign in with OIDC first to create the local account, then identify it using
 * a trusted database query. This command never creates identities or sessions.
 */
fun main() {
    val env = System.getenv()
    val input = bootstrapInput(env)
    requireNotNull(DatabaseRuntime.openIfConfigured(env)) { "DB_URL required" }.use { database ->
        val id = runBlocking { database.onDatabaseThread { FirstClubBootstrap(it).create(input) } }
        println("Created first club: $id")
    }
}

/**
 * Validates the operator environment before any database connection: the explicit
 * confirmation phrase and every required, non-blank value. Field rules (lengths, slug,
 * adult contacts) are enforced afterwards by [FirstClubBootstrap] and PostgreSQL.
 */
internal fun bootstrapInput(env: Map<String, String>): FirstClubBootstrap.Input {
    require(env["OPENCLUB_BOOTSTRAP_CONFIRM"] == "create-first-club") {
        "Explicit OPENCLUB_BOOTSTRAP_CONFIRM=create-first-club required"
    }
    fun required(key: String): String =
        requireNotNull(env[key]?.trim()?.takeIf(String::isNotEmpty)) { "$key required" }

    return FirstClubBootstrap.Input(
        accountId = canonicalUuid(required("OPENCLUB_BOOTSTRAP_ACCOUNT_ID"), "OPENCLUB_BOOTSTRAP_ACCOUNT_ID"),
        clubName = required("OPENCLUB_BOOTSTRAP_CLUB_NAME"),
        slug = required("OPENCLUB_BOOTSTRAP_CLUB_SLUG"),
        firstName = required("OPENCLUB_BOOTSTRAP_FIRST_NAME"),
        lastName = required("OPENCLUB_BOOTSTRAP_LAST_NAME"),
        birthDate = LocalDate.parse(required("OPENCLUB_BOOTSTRAP_BIRTH_DATE")),
        email = required("OPENCLUB_BOOTSTRAP_EMAIL"),
        phone = required("OPENCLUB_BOOTSTRAP_PHONE"),
    )
}

/**
 * Operator-supplied IDs must be in canonical 8-4-4-4-12 form: `UUID.fromString` also
 * accepts shortened spellings such as `1-1-1-1-1`, which would silently expand a typo.
 */
internal fun canonicalUuid(value: String, key: String): UUID {
    val trimmed = value.trim()
    val parsed = runCatching { UUID.fromString(trimmed) }.getOrNull()
    require(parsed != null && parsed.toString() == trimmed.lowercase()) { "$key must be a canonical UUID" }
    return parsed
}
