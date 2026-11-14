package org.openclub.api

import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * Resolves an identity ONLY after a trusted OIDC token verifier has produced
 * issuer + subject. Do not construct VerifiedOidcIdentity from request fields.
 *
 * New identities get a separate, unlinked person. Email, names, and club
 * membership are NEVER used to adopt an existing person or grant club roles.
 * Blocking JDBC: future Ktor handlers must use a bounded I/O dispatcher.
 */
internal class VerifiedPersonIdentities(
    private val database: DSLContext,
    private val settings: OidcSettings,
) {
    fun resolveOrCreate(identity: VerifiedOidcIdentity): UUID {
        require(identity.issuer == settings.issuer.toASCIIString()) { "Unexpected OIDC issuer" }
        // OIDC sub is a case-sensitive, nonempty ASCII string of at most 255 chars.
        require(identity.subject.length in 1..255 &&
            identity.subject.all { it.code in 0x21..0x7e }) { "Invalid OIDC subject" }

        find(database, identity)?.let { return it }
        return database.transactionResult { configuration ->
            val tx = DSL.using(configuration)
            find(tx, identity)?.let { return@transactionResult it }
            val candidate = UUID.randomUUID()
            tx.execute(
                "INSERT INTO persons (id, display_name) VALUES (?, ?)",
                candidate, "Unlinked OpenClub account",
            )
            val inserted = tx.execute(
                """INSERT INTO person_identities (id, person_id, issuer, subject)
                   VALUES (?, ?, ?, ?)
                   ON CONFLICT (issuer, subject) DO NOTHING""",
                UUID.randomUUID(), candidate, identity.issuer, identity.subject,
            )
            if (inserted == 1) {
                candidate
            } else {
                // Another concurrent login won the unique (issuer, subject) key.
                // Remove our unlinked candidate *inside the transaction*. PostgreSQL
                // READ COMMITTED can now see the committed winner on a fresh SELECT.
                tx.execute("DELETE FROM persons WHERE id = ?", candidate)
                find(tx, identity)
                    ?: throw IllegalStateException("Verified OIDC identity binding unavailable")
            }
        }
    }

    private fun find(db: DSLContext, identity: VerifiedOidcIdentity): UUID? =
        db.fetchOne(
            "SELECT person_id FROM person_identities WHERE issuer = ? AND subject = ?",
            identity.issuer, identity.subject,
        )?.get("person_id", UUID::class.java)
}
