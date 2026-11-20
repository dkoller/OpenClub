package org.openclub.api.calendar

import org.jooq.DSLContext
import org.openclub.api.members.ActorSql
import org.openclub.api.teams.TeamEvents
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * Private calendar feeds (OpenAPI 1.17.0, ADR-0008 addendum 21). A signed-in, active, verified member creates one
 * feed link for themselves; the link carries a 256-bit secret of which only the SHA-256 is stored, so it is shown
 * once. A calendar app loads the link without a session. The feed holds the same events as My schedule (the member's
 * own and their current minors' teams), four weeks back to six months ahead, with title, team, time, place, notes
 * and whether an event is cancelled; never people's names, answers or counts. It works only while the member is
 * active with a current verified identity, so archiving or losing the sign-in ends it at once.
 */
internal class CalendarFeeds(private val database: DSLContext) {
    data class Status(val active: Boolean, val createdAt: OffsetDateTime?)

    /** Null when the actor is not an active, verified member. */
    fun status(actor: UUID): Status? {
        val row = database.fetchOne(
            """SELECT f.created_at FROM members me LEFT JOIN calendar_feeds f ON f.member_id = me.id
               WHERE me.account_id = ? AND ${ActorSql.activeVerified("me")}""", actor) ?: return null
        val created = row.get(0, OffsetDateTime::class.java)?.withOffsetSameInstant(ZoneOffset.UTC)
        return Status(created != null, created)
    }

    /** Creates or replaces the actor's feed and returns its new secret, or null for a non-member. */
    fun create(actor: UUID): String? {
        val secret = newSecret()
        val created = database.execute(
            """INSERT INTO calendar_feeds (member_id, secret_hash)
               SELECT me.id, ? FROM members me WHERE me.account_id = ? AND ${ActorSql.activeVerified("me")}
               ON CONFLICT (member_id) DO UPDATE SET secret_hash = EXCLUDED.secret_hash, created_at = now()""",
            digest(secret), actor)
        return if (created == 1) secret else null
    }

    /** Deletes the actor's feed; false for a non-member. Revoking a feed that does not exist is still a success. */
    fun revoke(actor: UUID): Boolean {
        if (status(actor) == null) return false
        database.execute(
            """DELETE FROM calendar_feeds f USING members me
               WHERE f.member_id = me.id AND me.account_id = ? AND ${ActorSql.activeVerified("me")}""", actor)
        return true
    }

    /** The iCalendar text for a feed secret, or null for an unknown secret or a member who may no longer sign in. */
    fun feed(secret: String, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): String? {
        if (!SECRET.matches(secret)) return null
        val owner = database.fetchOne(
            """SELECT me.account_id, c.name FROM calendar_feeds f JOIN members me ON me.id = f.member_id
               CROSS JOIN clubs c
               WHERE f.secret_hash = ? AND ${ActorSql.activeVerified("me")} LIMIT 1""", digest(secret)) ?: return null
        val account = owner.get(0, UUID::class.java)
        val page = TeamEvents(database).mine(account, now.minus(PAST), now.plus(FUTURE)) ?: return null
        return Ical.calendar(owner.get(1, String::class.java), page.events, now)
    }

    companion object {
        val PAST: Duration = Duration.ofDays(28)
        val FUTURE: Duration = Duration.ofDays(183)
        /** 32 random bytes, base64url without padding. */
        val SECRET = Regex("^[A-Za-z0-9_-]{43}$")
        private val random = SecureRandom()

        fun newSecret(): String = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        fun digest(secret: String): String = MessageDigest.getInstance("SHA-256")
            .digest(secret.toByteArray(StandardCharsets.US_ASCII)).joinToString("") { "%02x".format(it) }
    }
}

/** A minimal RFC 5545 writer: CRLF lines folded at 75 octets, text escaped, all times in UTC. */
internal object Ical {
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    fun calendar(clubName: String, events: List<TeamEvents.Event>, now: OffsetDateTime): String {
        val lines = mutableListOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//OpenClub//Calendar feed//EN", "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH", "X-WR-CALNAME:${text(clubName)}", "REFRESH-INTERVAL;VALUE=DURATION:PT6H",
            "X-PUBLISHED-TTL:PT6H",
        )
        for (event in events) {
            lines += "BEGIN:VEVENT"
            lines += "UID:${event.id}@openclub"
            lines += "DTSTAMP:${time(now)}"
            lines += "DTSTART:${time(event.startsAt)}"
            lines += "DTEND:${time(event.endsAt)}"
            lines += "SUMMARY:${text("${event.teamName}: ${event.title}")}"
            event.location?.let { lines += "LOCATION:${text(it)}" }
            event.notes?.let { lines += "DESCRIPTION:${text(it)}" }
            lines += "STATUS:${if (event.cancelled) "CANCELLED" else "CONFIRMED"}"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    private fun time(value: OffsetDateTime) = stamp.format(value.withOffsetSameInstant(ZoneOffset.UTC))

    /** Escapes backslash, semicolon, comma and line breaks; drops other control characters. */
    fun text(value: String): String = buildString {
        for (char in value.replace("\r\n", "\n")) when {
            char == '\\' -> append("\\\\")
            char == ';' -> append("\\;")
            char == ',' -> append("\\,")
            char == '\n' || char == '\r' -> append("\\n")
            Character.isISOControl(char) -> Unit
            else -> append(char)
        }
    }

    /** Folds a line into chunks of at most 75 UTF-8 octets, never splitting a character. */
    fun fold(line: String): String {
        val out = StringBuilder()
        var octets = 0
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val size = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
            // Continuation lines start with one space, which counts towards their 75 octets.
            if (octets + size > 75) { out.append("\r\n "); octets = 1 }
            out.appendCodePoint(codePoint)
            octets += size
            index += chars
        }
        return out.toString()
    }
}
