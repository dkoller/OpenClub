package org.openclub.api.venues

import org.jooq.DSLContext
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Fields and halls (OpenAPI 1.30.0, ADR-0008 addendum 34). Every active member sees the venues and when each is
 * booked, by team and event title only; `MANAGE_VENUES` holders add, rename and delete them, checked inside the same
 * statement as each write. Venues are club content without personal data and are not audited.
 */
internal class Venues(private val database: DSLContext) {
    data class Venue(val id: UUID, val name: String)

    data class Booking(
        val eventId: UUID, val teamName: String, val kind: String, val title: String,
        val startsAt: OffsetDateTime, val endsAt: OffsetDateTime,
    )

    enum class Change { DONE, NOT_FOUND, DENIED, DUPLICATE }

    fun isMember(actor: UUID): Boolean = database.fetchOne(
        "SELECT 1 FROM members me WHERE me.account_id = ? AND ${ActorSql.activeVerified("me")}", actor) != null

    fun canManage(actor: UUID): Boolean = database.fetchOne(
        "SELECT ${ActorSql.holdsClubCapability(Capability.MANAGE_VENUES)}", actor)?.get(0, Boolean::class.java) == true

    fun list(): List<Venue> = database.fetch("SELECT id, name FROM venues ORDER BY lower(name), id")
        .map { Venue(it.get("id", UUID::class.java), it.get("name", String::class.java)) }

    fun exists(id: UUID): Boolean = database.fetchOne("SELECT 1 FROM venues WHERE id = ?", id) != null

    /** The venue's bookings (events that are not cancelled) overlapping [from]..[to], at most 500. */
    fun bookings(venue: UUID, from: OffsetDateTime, to: OffsetDateTime): List<Booking> = database.fetch(
        """SELECT e.id, t.name AS team_name, e.kind, e.title, e.starts_at, e.ends_at
           FROM team_events e JOIN teams t ON t.id = e.team_id
           WHERE e.venue_id = ? AND NOT e.cancelled AND e.ends_at > ?::timestamptz AND e.starts_at < ?::timestamptz
           ORDER BY e.starts_at, e.id LIMIT 500""", venue, from.toString(), to.toString()).map {
        Booking(it.get("id", UUID::class.java), it.get("team_name", String::class.java), it.get("kind", String::class.java),
            it.get("title", String::class.java),
            it.get("starts_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
            it.get("ends_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC))
    }

    sealed interface Created {
        data class Done(val id: UUID) : Created
        data object Denied : Created
        data object Duplicate : Created
    }

    fun create(actor: UUID, name: String): Created {
        val id = UUID.randomUUID()
        val inserted = unique {
            database.execute(
                "INSERT INTO venues (id, name) SELECT ?, ? WHERE ${ActorSql.holdsClubCapability(Capability.MANAGE_VENUES)}",
                id, name, actor)
        } ?: return Created.Duplicate
        return if (inserted == 1) Created.Done(id) else Created.Denied
    }

    fun rename(actor: UUID, id: UUID, name: String): Change {
        val updated = unique {
            database.execute(
                "UPDATE venues SET name = ? WHERE id = ? AND ${ActorSql.holdsClubCapability(Capability.MANAGE_VENUES)}",
                name, id, actor)
        } ?: return Change.DUPLICATE
        return outcome(actor, id, updated)
    }

    fun delete(actor: UUID, id: UUID): Change = outcome(actor, id, database.execute(
        "DELETE FROM venues WHERE id = ? AND ${ActorSql.holdsClubCapability(Capability.MANAGE_VENUES)}", id, actor))

    private fun outcome(actor: UUID, id: UUID, rows: Int): Change = when {
        rows == 1 -> Change.DONE
        !canManage(actor) -> Change.DENIED
        else -> Change.NOT_FOUND
    }

    /** Null when the name is already taken (unique index, case-insensitive). */
    private fun unique(write: () -> Int): Int? = try {
        write()
    } catch (error: org.jooq.exception.DataAccessException) {
        if (error.sqlState() == "23505") null else throw error
    }
}
