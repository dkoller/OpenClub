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
    require(env["OPENCLUB_BOOTSTRAP_CONFIRM"] == "create-first-club") {
        "Explicit OPENCLUB_BOOTSTRAP_CONFIRM=create-first-club required"
    }
    fun required(key: String): String =
        requireNotNull(env[key]?.trim()?.takeIf(String::isNotEmpty)) { "$key required" }

    val input = FirstClubBootstrap.Input(
        accountId = UUID.fromString(required("OPENCLUB_BOOTSTRAP_ACCOUNT_ID")),
        clubName = required("OPENCLUB_BOOTSTRAP_CLUB_NAME"),
        slug = required("OPENCLUB_BOOTSTRAP_CLUB_SLUG"),
        firstName = required("OPENCLUB_BOOTSTRAP_FIRST_NAME"),
        lastName = required("OPENCLUB_BOOTSTRAP_LAST_NAME"),
        birthDate = LocalDate.parse(required("OPENCLUB_BOOTSTRAP_BIRTH_DATE")),
        email = required("OPENCLUB_BOOTSTRAP_EMAIL"),
        phone = required("OPENCLUB_BOOTSTRAP_PHONE"),
    )
    requireNotNull(DatabaseRuntime.openIfConfigured(env)) { "DB_URL required" }.use { database ->
        val id = runBlocking { database.onDatabaseThread { FirstClubBootstrap(it).create(input) } }
        println("Created first club: $id")
    }
}
