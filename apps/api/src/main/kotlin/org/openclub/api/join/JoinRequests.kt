package org.openclub.api.join

import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import org.openclub.api.members.ClubCapabilityAccess
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.members.MemberCreation
import org.openclub.api.members.MemberFieldValidation
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Join requests (OpenAPI 1.22.0, ADR-0008 addendum 26). The public form works only while the club has switched it on
 * (`clubs.join_requests_open`, off by default). An adult applies for themselves, a parent for a child; the age is
 * checked against the club's adult age. At most [MAX_OPEN] open requests exist, the same person (address, first
 * name, birth date) at most once. Holders of `EDIT_MEMBERS` list, accept (creating the member with the usual
 * checks and the `MEMBER_CREATED` audit event, plus the wished team) or decline them. No IP address is stored and no
 * mail is sent. Open requests are deleted after 60 days.
 */
internal class JoinRequests(private val database: DSLContext) {
    enum class Kind { SELF, CHILD }

    data class Application(
        val kind: Kind, val firstName: String, val lastName: String, val dateOfBirth: LocalDate, val email: String,
        val phone: String, val parentFirstName: String?, val parentLastName: String?, val teamId: UUID?, val message: String?,
    )

    data class Request(val id: UUID, val createdAt: OffsetDateTime, val application: Application, val teamName: String?)

    enum class Submitted { ACCEPTED, CLOSED, FULL, INVALID }

    sealed interface Accepted {
        data class Done(val memberId: UUID) : Accepted
        data object NotFound : Accepted
        data object Denied : Accepted
        /** The member could not be created as requested, e.g. the child has come of age meanwhile. */
        data object Rejected : Accepted
    }

    /** Whether the form is open, and then the teams one may wish for (names only). */
    fun publicForm(): Pair<Boolean, List<Pair<UUID, String>>> {
        val open = database.fetchValue("SELECT bool_or(join_requests_open) FROM clubs") as Boolean? ?: false
        if (!open) return false to emptyList()
        return true to database.fetch("SELECT id, name FROM teams ORDER BY name, id")
            .map { it.get(0, UUID::class.java) to it.get(1, String::class.java) }
    }

    fun submit(application: Application): Submitted = database.transactionResult { config ->
        val db = org.jooq.impl.DSL.using(config)
        // The club row is locked, so concurrent submissions count one after the other and the cap of MAX_OPEN holds
        // (security review 2026-11-26).
        val open = db.fetchOne(
            """SELECT c.join_requests_open, (SELECT count(*) FROM join_requests) AS open_requests,
                      CASE WHEN ?::text = 'SELF' THEN ?::date <= (CURRENT_DATE - make_interval(years => c.adult_age_years))::date
                           ELSE ?::date > (CURRENT_DATE - make_interval(years => c.adult_age_years))::date END AS age_fits,
                      (?::uuid IS NULL OR EXISTS (SELECT 1 FROM teams WHERE id = ?::uuid)) AS team_fits
               FROM clubs c LIMIT 1 FOR NO KEY UPDATE OF c""",
            application.kind.name, application.dateOfBirth.toString(), application.dateOfBirth.toString(),
            application.teamId?.toString(), application.teamId?.toString()) ?: return@transactionResult Submitted.CLOSED
        if (open.get(0, Boolean::class.java) != true) return@transactionResult Submitted.CLOSED
        if (open.get(1, Int::class.java) >= MAX_OPEN) return@transactionResult Submitted.FULL
        if (open.get(2, Boolean::class.java) != true || open.get(3, Boolean::class.java) != true) return@transactionResult Submitted.INVALID
        // The same person already has an open request: answer as if accepted, revealing nothing.
        db.execute(
            """INSERT INTO join_requests (id, kind, first_name, last_name, date_of_birth, email, phone,
                   parent_first_name, parent_last_name, team_id, message)
               VALUES (?, ?, ?, ?, ?::date, ?, ?, ?, ?, ?::uuid, ?) ON CONFLICT DO NOTHING""",
            UUID.randomUUID(), application.kind.name, application.firstName, application.lastName,
            application.dateOfBirth.toString(), application.email, application.phone, application.parentFirstName,
            application.parentLastName, application.teamId?.toString(), application.message)
        Submitted.ACCEPTED
    }

    /** Null without `EDIT_MEMBERS`. */
    fun list(actor: UUID): List<Request>? {
        if (!ClubCapabilityAccess(database).has(actor, Capability.EDIT_MEMBERS)) return null
        return database.fetch(
            """SELECT r.*, t.name AS team_name FROM join_requests r LEFT JOIN teams t ON t.id = r.team_id
               WHERE ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}
               ORDER BY r.created_at, r.id LIMIT $MAX_OPEN""", actor).map {
            Request(it.get("id", UUID::class.java),
                it.get("created_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
                Application(Kind.valueOf(it.get("kind", String::class.java)), it.get("first_name", String::class.java),
                    it.get("last_name", String::class.java), it.get("date_of_birth", LocalDate::class.java),
                    it.get("email", String::class.java), it.get("phone", String::class.java),
                    it.get("parent_first_name", String::class.java), it.get("parent_last_name", String::class.java),
                    it.get("team_id", UUID::class.java), it.get("message", String::class.java)),
                it.get("team_name", String::class.java))
        }
    }

    /**
     * Creates the member from the request and deletes it, in one transaction: an adult with the given contacts, a child
     * without contacts (the guardian is linked as usual afterwards). The wished team is assigned if it still exists.
     */
    fun accept(actor: UUID, requestId: UUID): Accepted = try {
        database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            if (!ClubCapabilityAccess(db).has(actor, Capability.EDIT_MEMBERS)) return@transactionResult Accepted.Denied
            val row = db.fetchOne("SELECT * FROM join_requests WHERE id = ? FOR UPDATE", requestId)
                ?: return@transactionResult Accepted.NotFound
            val child = row.get("kind", String::class.java) == Kind.CHILD.name
            val member = MemberCreation(db).create(actor, MemberCreation.Input(
                row.get("first_name", String::class.java), row.get("last_name", String::class.java),
                row.get("date_of_birth", LocalDate::class.java),
                email = if (child) null else row.get("email", String::class.java),
                phone = if (child) null else row.get("phone", String::class.java),
            )) ?: return@transactionResult Accepted.Denied
            row.get("team_id", UUID::class.java)?.let {
                db.execute("INSERT INTO member_team_assignments (team_id, member_id) SELECT id, ? FROM teams WHERE id = ?", member, it)
            }
            db.execute("DELETE FROM join_requests WHERE id = ?", requestId)
            Accepted.Done(member)
        }
    } catch (failure: DataAccessException) {
        // A database rule refused the member (for example a child who has come of age needs contacts).
        val state = failure.sqlState()
        if (state.startsWith("22") || state.startsWith("23") || state == "P0001") Accepted.Rejected else throw failure
    } catch (_: IllegalArgumentException) {
        Accepted.Rejected
    }

    /** Declines (deletes) a request; null without `EDIT_MEMBERS`, false for an unknown ID. */
    fun decline(actor: UUID, requestId: UUID): Boolean? {
        val deleted = database.execute(
            "DELETE FROM join_requests WHERE id = ? AND ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}",
            requestId, actor) == 1
        if (deleted) return true
        return if (ClubCapabilityAccess(database).has(actor, Capability.EDIT_MEMBERS)) false else null
    }

    /** The club-wide switch; null without `EDIT_MEMBERS`. */
    fun settings(actor: UUID): Boolean? =
        if (!ClubCapabilityAccess(database).has(actor, Capability.EDIT_MEMBERS)) null
        else database.fetchValue("SELECT bool_or(join_requests_open) FROM clubs") as Boolean? ?: false

    /** Switches the form on or off; false without `EDIT_MEMBERS`. */
    fun setOpen(actor: UUID, open: Boolean): Boolean = database.execute(
        "UPDATE clubs SET join_requests_open = ? WHERE ${ActorSql.holdsClubCapability(Capability.EDIT_MEMBERS)}",
        open, actor) >= 1

    companion object {
        const val MAX_OPEN = 200

        /** Shared field rules; returns null for anything invalid. */
        fun validate(
            kind: String, firstName: String, lastName: String, dateOfBirth: LocalDate, email: String, phone: String,
            parentFirstName: String?, parentLastName: String?, teamId: UUID?, message: String?,
        ): Application? = try {
            val type = Kind.valueOf(kind)
            val parentFirst = parentFirstName?.let(MemberFieldValidation::name)
            val parentLast = parentLastName?.let(MemberFieldValidation::name)
            require((type == Kind.SELF) == (parentFirst == null && parentLast == null))
            require(type == Kind.SELF || (parentFirst != null && parentLast != null))
            val text = message?.trim()?.ifEmpty { null }
            require(text == null || (text.length <= 500 && text.none { Character.isISOControl(it) && it != '\n' }))
            Application(type, MemberFieldValidation.name(firstName), MemberFieldValidation.name(lastName),
                MemberFieldValidation.birthDate(dateOfBirth), requireNotNull(MemberFieldValidation.email(email)),
                requireNotNull(MemberFieldValidation.phone(phone)), parentFirst, parentLast, teamId, text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** At most [limit] submissions per client address per hour, kept only in memory (never stored). */
internal class JoinRequestLimiter(private val limit: Int = 5, private val windowMillis: Long = 3_600_000) {
    private val seen = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun allow(client: String, now: Long = System.currentTimeMillis()): Boolean {
        if (seen.size > 10_000) seen.entries.removeIf { (_, times) -> synchronized(times) { times.all { now - it > windowMillis } } }
        val times = seen.computeIfAbsent(client) { ArrayDeque() }
        synchronized(times) {
            while (times.isNotEmpty() && now - times.first() > windowMillis) times.removeFirst()
            if (times.size >= limit) return false
            times.addLast(now)
            return true
        }
    }
}
