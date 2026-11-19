package org.openclub.api.fees

import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import java.time.LocalDate
import java.util.UUID

/**
 * Membership fees (OpenAPI 1.12.0, ADR-0008 addendum 15). Tracking only. Every change needs the club-wide
 * `MANAGE_FEES` capability, and each write transaction first takes a share lock on the actor's live grant (and their
 * member row), so a revocation either waits for the write or is seen by it. Every change to one member's fee is
 * audited with identifiers only: FEE_ASSIGNED, FEE_CHANGED, FEE_PAID, FEE_UNPAID, FEE_REMOVED.
 */
internal class MembershipFees(private val database: DSLContext) {
    data class Period(
        val id: UUID, val name: String, val dueDate: LocalDate, val amountCents: Int,
        val members: Int, val paid: Int, val totalCents: Long, val paidCents: Long,
    )

    /** One member's fee in a period; [amountCents] is null when the active member is excluded. */
    data class Fee(
        val memberId: UUID, val firstName: String, val lastName: String, val archived: Boolean,
        val amountCents: Int?, val paidOn: LocalDate?,
    )

    data class MyFee(
        val periodId: UUID, val periodName: String, val dueDate: LocalDate, val amountCents: Int, val paidOn: LocalDate?,
        val forId: UUID, val forFirstName: String,
    )

    enum class Filter { ALL, OPEN, PAID, EXCLUDED }

    enum class Change { DONE, NOT_FOUND, CONFLICT, DENIED }

    sealed interface Listing {
        data class Found(val fees: List<Fee>) : Listing
        data object NotFound : Listing
        data object Denied : Listing
    }

    sealed interface Created {
        data class Done(val id: UUID) : Created
        data object Conflict : Created
        data object Denied : Created
    }

    /** Locks the actor's live MANAGE_FEES grant and member row for the rest of the transaction; false without one. */
    private fun lockManager(db: DSLContext, actor: UUID): Boolean = db.fetchOne(
        """SELECT 1 FROM members actor JOIN club_capability_grants g ON g.member_id = actor.id AND g.capability = 'MANAGE_FEES'
           WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")}
           FOR SHARE OF actor, g""", actor) != null

    private fun audit(db: DSLContext, type: String, actor: UUID, member: UUID) = db.execute(
        "INSERT INTO audit_events (event_type, actor_account_id, target_member_id) VALUES (?, ?, ?)", type, actor, member)

    fun periods(actor: UUID): List<Period>? = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        if (!lockManager(db, actor)) return@transactionResult null
        db.fetch(
            """SELECT p.id, p.name, p.due_date, p.amount_cents,
                      count(f.member_id) AS members, count(f.paid_on) AS paid,
                      COALESCE(sum(f.amount_cents), 0) AS total_cents,
                      COALESCE(sum(f.amount_cents) FILTER (WHERE f.paid_on IS NOT NULL), 0) AS paid_cents
               FROM fee_periods p LEFT JOIN member_fees f ON f.period_id = p.id
               GROUP BY p.id ORDER BY p.due_date DESC, p.name, p.id""").map {
            Period(it.get("id", UUID::class.java), it.get("name", String::class.java), it.get("due_date", LocalDate::class.java),
                it.get("amount_cents", Int::class.java), it.get("members", Int::class.java), it.get("paid", Int::class.java),
                it.get("total_cents", Long::class.java), it.get("paid_cents", Long::class.java))
        }
    }

    /** Creates the period and includes every active member at its amount, all in one transaction. */
    fun create(actor: UUID, name: String, dueDate: LocalDate, amountCents: Int): Created = try {
        database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            if (!lockManager(db, actor)) return@transactionResult Created.Denied
            val id = UUID.randomUUID()
            db.execute("INSERT INTO fee_periods (id, name, due_date, amount_cents) VALUES (?, ?, ?::date, ?)",
                id, name, dueDate.toString(), amountCents)
            db.execute(
                """WITH included AS (
                     INSERT INTO member_fees (period_id, member_id, amount_cents)
                     SELECT ?, m.id, ? FROM members m WHERE m.archived_at IS NULL
                     RETURNING member_id)
                   INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
                   SELECT 'FEE_ASSIGNED', ?, member_id FROM included""", id, amountCents, actor)
            Created.Done(id)
        }
    } catch (failure: DataAccessException) {
        if (failure.sqlState() == "23505") Created.Conflict else throw failure
    }

    fun rename(actor: UUID, periodId: UUID, name: String, dueDate: LocalDate): Change = try {
        database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            if (!lockManager(db, actor)) return@transactionResult Change.DENIED
            val changed = db.execute("UPDATE fee_periods SET name = ?, due_date = ?::date WHERE id = ?",
                name, dueDate.toString(), periodId) == 1
            if (changed) Change.DONE else Change.NOT_FOUND
        }
    } catch (failure: DataAccessException) {
        if (failure.sqlState() == "23505") Change.CONFLICT else throw failure
    }

    /** Deletes the period and every fee in it once [confirmation] repeats its name; each removed fee is audited. */
    fun delete(actor: UUID, periodId: UUID, confirmation: String): Change = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        if (!lockManager(db, actor)) return@transactionResult Change.DENIED
        val name = db.fetchOne("SELECT name FROM fee_periods WHERE id = ? FOR UPDATE", periodId)
            ?.get(0, String::class.java) ?: return@transactionResult Change.NOT_FOUND
        if (name != confirmation) return@transactionResult Change.CONFLICT
        db.execute(
            """INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
               SELECT 'FEE_REMOVED', ?, member_id FROM member_fees WHERE period_id = ?""", actor, periodId)
        db.execute("DELETE FROM fee_periods WHERE id = ?", periodId)
        Change.DONE
    }

    /** The fees of one period; EXCLUDED lists active members without a fee. */
    fun fees(actor: UUID, periodId: UUID, filter: Filter): Listing = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        if (!lockManager(db, actor)) return@transactionResult Listing.Denied
        if (db.fetchOne("SELECT 1 FROM fee_periods WHERE id = ?", periodId) == null) return@transactionResult Listing.NotFound
        val condition = when (filter) {
            Filter.ALL -> "f.member_id IS NOT NULL"
            Filter.OPEN -> "f.member_id IS NOT NULL AND f.paid_on IS NULL"
            Filter.PAID -> "f.paid_on IS NOT NULL"
            Filter.EXCLUDED -> "f.member_id IS NULL AND m.archived_at IS NULL"
        }
        Listing.Found(db.fetch(
            """SELECT m.id, m.first_name, m.last_name, m.archived_at IS NOT NULL AS archived, f.amount_cents, f.paid_on
               FROM members m LEFT JOIN member_fees f ON f.member_id = m.id AND f.period_id = ?
               WHERE $condition
               ORDER BY m.last_name, m.first_name, m.id""", periodId).map {
            Fee(it.get("id", UUID::class.java), it.get("first_name", String::class.java), it.get("last_name", String::class.java),
                // Nullable for an excluded member: the boxed type, so jOOQ does not turn NULL into 0.
                it.get("archived", Boolean::class.java), it.get("amount_cents", Int::class.javaObjectType),
                it.get("paid_on", LocalDate::class.java))
        })
    }

    /**
     * Sets one member's amount and payment, including them if they were excluded (active members only; an archived
     * member's existing fee can still be marked paid). Audits each kind of change separately.
     */
    fun set(actor: UUID, periodId: UUID, memberId: UUID, amountCents: Int, paidOn: LocalDate?): Change =
        database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            if (!lockManager(db, actor)) return@transactionResult Change.DENIED
            // FOR NO KEY UPDATE serializes changes within one period (two first-time sets cannot both insert), without
            // blocking the foreign-key checks of the fee rows.
            if (db.fetchOne("SELECT 1 FROM fee_periods WHERE id = ? FOR NO KEY UPDATE", periodId) == null) return@transactionResult Change.NOT_FOUND
            val member = db.fetchOne("SELECT archived_at IS NOT NULL FROM members WHERE id = ? FOR SHARE", memberId)
                ?: return@transactionResult Change.NOT_FOUND
            val current = db.fetchOne(
                "SELECT amount_cents, paid_on FROM member_fees WHERE period_id = ? AND member_id = ? FOR UPDATE", periodId, memberId)
            if (current == null) {
                if (member.get(0, Boolean::class.java)) return@transactionResult Change.CONFLICT
                db.execute("INSERT INTO member_fees (period_id, member_id, amount_cents, paid_on) VALUES (?, ?, ?, ?::date)",
                    periodId, memberId, amountCents, paidOn?.toString())
                audit(db, "FEE_ASSIGNED", actor, memberId)
                if (paidOn != null) audit(db, "FEE_PAID", actor, memberId)
                return@transactionResult Change.DONE
            }
            val oldAmount = current.get(0, Int::class.java)
            val oldPaid = current.get(1, LocalDate::class.java)
            db.execute("UPDATE member_fees SET amount_cents = ?, paid_on = ?::date WHERE period_id = ? AND member_id = ?",
                amountCents, paidOn?.toString(), periodId, memberId)
            if (oldAmount != amountCents) audit(db, "FEE_CHANGED", actor, memberId)
            if (oldPaid != paidOn) audit(db, if (paidOn == null) "FEE_UNPAID" else "FEE_PAID", actor, memberId)
            Change.DONE
        }

    /** Excludes the member from the period; excluding someone without a fee is a success that audits nothing. */
    fun remove(actor: UUID, periodId: UUID, memberId: UUID): Change = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        if (!lockManager(db, actor)) return@transactionResult Change.DENIED
        if (db.fetchOne("SELECT 1 FROM fee_periods WHERE id = ? FOR SHARE", periodId) == null) return@transactionResult Change.NOT_FOUND
        if (db.execute("DELETE FROM member_fees WHERE period_id = ? AND member_id = ?", periodId, memberId) == 1) {
            audit(db, "FEE_REMOVED", actor, memberId)
        }
        Change.DONE
    }

    /** The actor's own fees and those of their current minors (the *My children* rule); null for a non-member. */
    fun mine(actor: UUID): List<MyFee>? {
        if (database.fetchOne("SELECT 1 FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}", actor) == null) {
            return null
        }
        return database.fetch(
            """WITH me AS (SELECT m.id, m.first_name FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}),
                    people AS (
                      SELECT me.id, me.first_name FROM me
                      UNION
                      SELECT minor.id, minor.first_name
                      FROM me JOIN member_guardianships gs ON gs.guardian_member_id = me.id
                      JOIN members guardian ON guardian.id = gs.guardian_member_id
                      JOIN members minor ON minor.id = gs.minor_member_id
                      JOIN clubs c ON TRUE
                      WHERE ${AgeSql.adult("guardian")} AND minor.archived_at IS NULL AND ${AgeSql.minor("minor")})
               SELECT p.id, p.name, p.due_date, f.amount_cents, f.paid_on, people.id AS for_id, people.first_name AS for_name
               FROM people JOIN member_fees f ON f.member_id = people.id JOIN fee_periods p ON p.id = f.period_id
               ORDER BY p.due_date DESC, p.name, people.first_name, people.id""", actor).map {
            MyFee(it.get("id", UUID::class.java), it.get("name", String::class.java), it.get("due_date", LocalDate::class.java),
                it.get("amount_cents", Int::class.java), it.get("paid_on", LocalDate::class.java),
                it.get("for_id", UUID::class.java), it.get("for_name", String::class.java))
        }
    }
}
