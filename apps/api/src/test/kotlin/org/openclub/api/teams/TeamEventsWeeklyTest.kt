package org.openclub.api.teams

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Weekly copies keep the local time in the caller's zone, include the end date and stop at 52. Database-free. */
class TeamEventsWeeklyTest {
    private val zurich = ZoneId.of("Europe/Zurich")

    @Test
    fun aWeeklyTrainingKeepsItsLocalTimeAcrossDaylightSaving() {
        // 18:00 in Zurich on Thursday 2026-03-19 (UTC+1); daylight saving starts on 2026-03-29.
        val start = OffsetDateTime.parse("2026-03-19T18:00:00+01:00")
        val starts = TeamEvents.weeklyStarts(start, LocalDate.parse("2026-04-09"), zurich)!!
        assertEquals(4, starts.size)
        assertEquals(listOf("2026-03-19", "2026-03-26", "2026-04-02", "2026-04-09"),
            starts.map { it.atZoneSameInstant(zurich).toLocalDate().toString() })
        starts.forEach { assertEquals(LocalTime.of(18, 0), it.atZoneSameInstant(zurich).toLocalTime()) }
        assertEquals("+02:00", starts.last().offset.id, "after the change the same local time is a different instant")
    }

    @Test
    fun boundsAreEnforced() {
        val start = OffsetDateTime.parse("2026-01-05T18:00:00+01:00")
        assertEquals(listOf(start), TeamEvents.weeklyStarts(start, null, null))
        assertEquals(1, TeamEvents.weeklyStarts(start, LocalDate.parse("2026-01-05"), zurich)!!.size)
        assertNull(TeamEvents.weeklyStarts(start, LocalDate.parse("2026-01-04"), zurich))
        assertEquals(52, TeamEvents.weeklyStarts(start, start.toLocalDate().plusWeeks(51), zurich)!!.size)
        assertNull(TeamEvents.weeklyStarts(start, start.toLocalDate().plusWeeks(52), zurich))
    }

    @Test
    fun everyTwoWeeksUpToTheEndDate() {
        val start = OffsetDateTime.parse("2026-01-05T18:00:00+01:00")
        val starts = TeamEvents.weeklyStarts(start, LocalDate.parse("2026-02-02"), zurich, every = 2)!!
        assertEquals(listOf("2026-01-05", "2026-01-19", "2026-02-02"), starts.map { it.atZoneSameInstant(zurich).toLocalDate().toString() })
        assertEquals(52, TeamEvents.weeklyStarts(start, start.toLocalDate().plusWeeks(4 * 51), zurich, every = 4)!!.size)
    }
}
