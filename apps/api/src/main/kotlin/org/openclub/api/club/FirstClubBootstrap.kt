package org.openclub.api.club

import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.util.UUID

/** Privileged, once-only operator action. Never expose through an HTTP route. */
internal class FirstClubBootstrap(private val database: DSLContext) {
    data class Input(
        val accountId: UUID,
        val clubName: String,
        val slug: String,
        val firstName: String,
        val lastName: String,
        val birthDate: LocalDate,
        val email: String,
        val phone: String,
    )

    fun create(input: Input): UUID {
        require(input.clubName.trim().length in 1..200) { "Invalid club name" }
        require(input.slug.length in 3..60 &&
            Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(input.slug)) { "Invalid club slug" }
        require(input.firstName.trim().length in 1..200 && input.lastName.trim().length in 1..200) {
            "Invalid administrator name"
        }
        require(input.birthDate.isBefore(LocalDate.now()) && input.birthDate.isAfter(LocalDate.of(1850, 1, 1))) {
            "Invalid administrator birth date"
        }
        require(input.email.trim().length in 3..320 && input.phone.trim().length in 1..40) {
            "Administrator email and phone required"
        }
        return database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            // Serialize competing bootstrap attempts, even from different processes.
            db.execute("SELECT pg_advisory_xact_lock(672018, 1)")
            check(db.fetchValue("SELECT COUNT(*) FROM clubs", Int::class.java) == 0) {
                "Club already initialized"
            }
            check(db.fetchValue("SELECT COUNT(*) FROM members", Int::class.java) == 0) {
                "Existing members require operator investigation"
            }
            check(db.fetchValue(
                "SELECT COUNT(*) FROM account_identities WHERE account_id = ?",
                Int::class.java, input.accountId,
            )!! > 0) { "Bootstrap account must have a verified OIDC identity" }
            val clubId = UUID.randomUUID()
            db.execute(
                "INSERT INTO clubs (id, name, slug) VALUES (?, ?, ?)",
                clubId, input.clubName.trim(), input.slug,
            )
            db.execute(
                """INSERT INTO members
                   (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                   VALUES (?, ?, ?, ?, ?, ?, ?, 'ADMIN')""",
                UUID.randomUUID(), input.accountId, input.firstName.trim(), input.lastName.trim(),
                input.birthDate, input.email.trim(), input.phone.trim(),
            )
            clubId
        }
    }
}
