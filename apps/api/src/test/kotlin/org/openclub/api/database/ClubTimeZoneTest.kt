package org.openclub.api.database

import kotlinx.coroutines.runBlocking
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * OPENCLUB_TIME_ZONE (since 2026-11-27 everywhere, not only emails and absences): the setting is read and checked, and
 * the database's day calculations follow it rather than a fixed Europe/Zurich.
 */
class ClubTimeZoneTest {
    @Test
    fun readsTheSettingWithEuropeZurichAsDefault() {
        assertEquals("Europe/Zurich", DatabaseRuntime.clubTimeZone(emptyMap()))
        assertEquals("Europe/Zurich", DatabaseRuntime.clubTimeZone(mapOf("OPENCLUB_TIME_ZONE" to " ")))
        assertEquals("America/New_York", DatabaseRuntime.clubTimeZone(mapOf("OPENCLUB_TIME_ZONE" to "America/New_York")))
        for (bad in listOf("UTC+1", "Europe/Nowhere", "Europe/Zurich; DROP")) {
            assertThrows(Exception::class.java, { DatabaseRuntime.clubTimeZone(mapOf("OPENCLUB_TIME_ZONE" to bad)) }, bad)
        }
    }

    /** An event at 23:30 UTC is the next day in Zurich but the same day in New York; an absence counts on its local day. */
    @Test
    fun anAbsenceCountsOnTheEventsDayInTheClubsTimeZone() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        val club = UUID.randomUUID()
        val team = UUID.randomUUID()
        val member = UUID.randomUUID()
        val event = UUID.randomUUID()
        try {
            runBlocking {
                database.onDatabaseThread { db ->
                    // Member fields are checked against the club's settings, so the club comes first.
                    db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Zone Club', ?)", club, "zone-$club")
                    db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Zone ${team.toString().take(8)}")
                    db.execute("""INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone)
                                  VALUES (?, 'Zone', 'Test', DATE '1980-01-01', 'z@example.invalid', '+41000000071')""", member)
                    db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, member)
                    db.execute("""INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at)
                                  VALUES (?, ?, 'TRAINING', 'Late', TIMESTAMPTZ '2030-06-10 23:30Z', TIMESTAMPTZ '2030-06-11 00:30Z')""", event, team)
                    // Absent on 11 June: the event's day in Zurich (01:30 on 11 June), not in New York (19:30 on 10 June).
                    db.execute("INSERT INTO member_absences (id, member_id, from_date, to_date) VALUES (?, ?, DATE '2030-06-11', DATE '2030-06-11')",
                        UUID.randomUUID(), member)
                    fun implied(zone: String): Int = db.transactionResult { c ->
                        val tx = DSL.using(c)
                        tx.execute("SELECT set_config('openclub.time_zone', ?, true)", zone)
                        // Kotlin reads the same setting (helper season, presence export).
                        assertEquals(zone, tx.clubZone().id)
                        (tx.fetchValue("SELECT count(*) FROM effective_attendance WHERE event_id = ? AND member_id = ?", event, member) as Number).toInt()
                    }
                    assertEquals(1, implied("Europe/Zurich"))
                    assertEquals(0, implied("America/New_York"))
                }
            }
        } finally {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("DELETE FROM teams WHERE id = ?", team)
                    db.execute("DELETE FROM members WHERE id = ?", member)
                    db.execute("DELETE FROM clubs WHERE id = ?", club)
                }
            }
            database.close()
        }
    }
}
