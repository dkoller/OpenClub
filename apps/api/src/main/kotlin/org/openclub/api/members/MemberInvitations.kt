package org.openclub.api.members

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.database.AuditEvents
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Invitations so that an administrator-managed adult member can sign in themselves (invitation design,
 * owner-approved 2026-11-18). Linking needs **two proofs**: the invitation secret and a verified OIDC session
 * account. The secret is 256 random bits; only its SHA-256 hash is stored. Linking grants nothing: the member
 * keeps role MEMBER and no capability. Email and name are never inputs.
 *
 * Lock order is always account, then invitation, then member, so concurrent calls cannot deadlock; [revoke]
 * deliberately never locks the member row.
 */
internal class MemberInvitations(
    private val database: DSLContext,
    private val random: SecureRandom = SecureRandom(),
) {
    sealed interface CreateResult {
        data class Created(val secret: String, val expiresAt: Instant) : CreateResult
        data object Denied : CreateResult
        data object NotFound : CreateResult
        data object Conflict : CreateResult
    }

    enum class RevokeResult { REVOKED, NOTHING_OPEN, DENIED, NOT_FOUND }

    /** Every failure to accept is the same value, so a caller learns nothing about members or invitations. */
    enum class AcceptResult { ACCEPTED, REFUSED }

    /**
     * Creates the single open invitation for an active, unlinked, adult, ordinary member, under a current
     * verified club-wide EDIT_MEMBERS grant. Minors are not invited: a guardian signs in for a minor (ADR-0008).
     */
    fun create(actorAccountId: UUID, memberId: UUID): CreateResult = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        if (!holdsEditMembers(db, actorAccountId)) return@transactionResult CreateResult.Denied
        val member = db.fetchOne(
            """SELECT m.role, m.account_id IS NOT NULL AS linked,
                      m.date_of_birth <= (CURRENT_DATE - make_interval(years => c.adult_age_years))::date AS adult
               FROM members m CROSS JOIN clubs c WHERE m.id = ? AND m.archived_at IS NULL FOR UPDATE OF m""",
            memberId,
        ) ?: return@transactionResult CreateResult.NotFound
        if (member.get("linked", Boolean::class.java) || member.get("role", String::class.java) != "MEMBER" ||
            !member.get("adult", Boolean::class.java)) return@transactionResult CreateResult.Conflict

        // An expired, never-used invitation is dead; retire it so it cannot block a new one.
        db.execute(
            """UPDATE member_invitations SET revoked_at = now()
               WHERE member_id = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at <= now()""", memberId)
        if (db.fetchOne(
                "SELECT 1 FROM member_invitations WHERE member_id = ? AND used_at IS NULL AND revoked_at IS NULL", memberId,
            ) != null) return@transactionResult CreateResult.Conflict

        val secret = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expiresAt = Instant.now().plusSeconds(LIFETIME_DAYS * 86_400)
        db.execute(
            """INSERT INTO member_invitations (id, member_id, secret_hash, created_by_account_id, expires_at)
               VALUES (?, ?, ?, ?, ?::timestamptz)""",
            UUID.randomUUID(), memberId, digest(secret), actorAccountId, expiresAt.toString())
        AuditEvents.record(db, AuditEvents.Type.INVITATION_CREATED, actorAccountId, memberId)
        CreateResult.Created(secret, expiresAt)
    }

    /** Revokes the member's open invitation. Idempotent as to the outcome: nothing is open afterwards. */
    fun revoke(actorAccountId: UUID, memberId: UUID): RevokeResult = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        if (!holdsEditMembers(db, actorAccountId)) return@transactionResult RevokeResult.DENIED
        if (db.fetchOne("SELECT 1 FROM members WHERE id = ?", memberId) == null) return@transactionResult RevokeResult.NOT_FOUND
        val revoked = db.execute(
            """UPDATE member_invitations SET revoked_at = now()
               WHERE member_id = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > now()""", memberId)
        if (revoked == 0) return@transactionResult RevokeResult.NOTHING_OPEN
        AuditEvents.record(db, AuditEvents.Type.INVITATION_REVOKED, actorAccountId, memberId)
        RevokeResult.REVOKED
    }

    /**
     * Links the caller's verified account to the invited member in one transaction. Refused, with no change,
     * unless the secret names an open invitation, the member is still active, unlinked, ordinary and adult, and
     * the account has a verified identity and no member yet. Racing accepts of one secret have exactly one winner.
     */
    fun accept(accountId: UUID, secret: String): AcceptResult {
        if (!validSecret(secret)) return AcceptResult.REFUSED
        return database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            db.fetchOne(
                """SELECT 1 FROM accounts a
                   WHERE a.id = ? AND EXISTS (SELECT 1 FROM account_identities i WHERE i.account_id = a.id)
                     AND NOT EXISTS (SELECT 1 FROM members m WHERE m.account_id = a.id)
                   FOR UPDATE OF a""", accountId,
            ) ?: return@transactionResult AcceptResult.REFUSED
            val invitation = db.fetchOne(
                """SELECT id, member_id FROM member_invitations
                   WHERE secret_hash = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > now() FOR UPDATE""",
                digest(secret),
            ) ?: return@transactionResult AcceptResult.REFUSED
            val memberId = invitation.get("member_id", UUID::class.java)
            db.fetchOne(
                """SELECT 1 FROM members m CROSS JOIN clubs c
                   WHERE m.id = ? AND m.archived_at IS NULL AND m.account_id IS NULL AND m.role = 'MEMBER'
                     AND m.date_of_birth <= (CURRENT_DATE - make_interval(years => c.adult_age_years))::date
                   FOR UPDATE OF m""", memberId,
            ) ?: return@transactionResult AcceptResult.REFUSED
            check(db.execute("UPDATE members SET account_id = ? WHERE id = ? AND account_id IS NULL", accountId, memberId) == 1) {
                "Invited member changed while the accept locks were held"
            }
            db.execute(
                "UPDATE member_invitations SET used_at = now(), accepted_by_account_id = ? WHERE id = ?",
                accountId, invitation.get("id", UUID::class.java))
            AuditEvents.record(db, AuditEvents.Type.INVITATION_ACCEPTED, accountId, memberId)
            AcceptResult.ACCEPTED
        }
    }

    private fun holdsEditMembers(db: DSLContext, actorAccountId: UUID): Boolean = db.fetchOne(
        """SELECT 1 FROM members actor
           JOIN club_capability_grants g ON g.member_id = actor.id
           WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")} AND g.capability = 'EDIT_MEMBERS'
           FOR SHARE OF actor, g""", actorAccountId) != null

    private fun validSecret(value: String): Boolean =
        value.length in 43..128 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    private fun digest(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(StandardCharsets.US_ASCII)).joinToString("") { "%02x".format(it) }

    companion object { const val LIFETIME_DAYS = 7L }
}
