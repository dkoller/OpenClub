package org.openclub.api.calendar

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.openclub.api.teams.EventAttendance
import org.openclub.api.teams.TeamEvents
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID

/** The iCalendar writer of the calendar feed (OpenAPI 1.17.0), without a database. */
class IcalTest {
    private val now = OffsetDateTime.parse("2026-11-20T08:00:00Z")
    private fun event(notes: String? = null, cancelled: Boolean = false) = TeamEvents.Event(
        UUID.fromString("00000000-0000-0000-0000-000000000001"), UUID.randomUUID(), "U18", TeamEvents.Kind.TRAINING,
        "Training; hall, B", OffsetDateTime.parse("2026-11-25T18:00:00+01:00"), OffsetDateTime.parse("2026-11-25T19:30:00+01:00"),
        "Hall\\2", notes, cancelled, EventAttendance.Counts(3, 1, 0))

    @Test
    fun eventsAreWrittenInUtcWithEscapedTextAndNoCounts() {
        val text = Ical.calendar("FC Example", listOf(event(notes = "Bring\r\nshoes")), now)
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        for (line in listOf("UID:00000000-0000-0000-0000-000000000001@openclub", "DTSTAMP:20261120T080000Z",
            "DTSTART:20261125T170000Z", "DTEND:20261125T183000Z", "SUMMARY:U18: Training\\; hall\\, B",
            "LOCATION:Hall\\\\2", "DESCRIPTION:Bring\\nshoes", "STATUS:CONFIRMED", "X-WR-CALNAME:FC Example")) {
            assertTrue("$line\r\n" in text, line)
        }
        assertFalse("3" in text.lines().filter { it.startsWith("SUMMARY") || it.startsWith("DESCRIPTION") }.joinToString(),
            "no answer counts")
        assertTrue("STATUS:CANCELLED" in Ical.calendar("C", listOf(event(cancelled = true)), now))
        assertFalse("DESCRIPTION" in Ical.calendar("C", listOf(event()), now), "no empty notes")
    }

    @Test
    fun longLinesAreFoldedAt75OctetsWithoutSplittingCharacters() {
        val folded = Ical.fold("DESCRIPTION:" + "ä".repeat(80))
        val lines = folded.split("\r\n")
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.toByteArray(StandardCharsets.UTF_8).size <= 75 })
        assertTrue(lines.drop(1).all { it.startsWith(" ") })
        assertEquals("DESCRIPTION:" + "ä".repeat(80), lines.first() + lines.drop(1).joinToString("") { it.substring(1) })
        assertEquals("\\n", Ical.text("\n"))
        assertEquals("", Ical.text("\u0007"))
    }

    @Test
    fun secretsAreLongRandomAndOnlyTheirHashIsKept() {
        val secrets = List(50) { CalendarFeeds.newSecret() }
        assertEquals(50, secrets.toSet().size)
        assertTrue(secrets.all { CalendarFeeds.SECRET.matches(it) })
        assertTrue(Regex("^[0-9a-f]{64}$").matches(CalendarFeeds.digest(secrets[0])))
        assertFalse(CalendarFeeds.SECRET.matches(secrets[0] + "x"))
        assertFalse(CalendarFeeds.SECRET.matches("../" + secrets[0].drop(3)))
    }
}
