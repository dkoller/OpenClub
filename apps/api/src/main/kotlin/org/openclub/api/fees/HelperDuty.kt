package org.openclub.api.fees

import org.jooq.DSLContext
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Helper duty (OpenAPI 1.32.0, ADR-0008 addendum 36): how many helper shifts each liable person has done this season
 * against the club's quota. Liable are active adults who are in a team or are the guardian of an active minor in a
 * team. A shift counts once it has started, its event was not cancelled, for the volunteer and for the guardians of a
 * current minor volunteer. The season starts on the first day of the club's season month (Swiss dates).
 */
internal class HelperDuty(private val database: DSLContext) {
    data class Settings(val quota: Int, val seasonStartMonth: Int)
    data class Season(val from: LocalDate, val to: LocalDate)
    data class Row(val memberId: UUID, val displayName: String, val done: Int)

    fun settings(): Settings = database.fetchOne("SELECT helper_quota, season_start_month FROM clubs LIMIT 1")
        ?.let { Settings(it.get(0, Int::class.java), it.get(1, Int::class.java)) } ?: Settings(0, 8)

    fun season(settings: Settings, today: LocalDate = LocalDate.now(SWISS)): Season {
        val year = if (today.monthValue >= settings.seasonStartMonth) today.year else today.year - 1
        val from = LocalDate.of(year, settings.seasonStartMonth, 1)
        return Season(from, from.plusYears(1).minusDays(1))
    }

    /** The shifts done by [person] (a SQL expression for the member's ID) this season. Binds: from, to. */
    private fun doneSql(person: String) = """
        (SELECT count(*) FROM shift_volunteers v
         JOIN event_shifts s ON s.id = v.shift_id
         JOIN team_events e ON e.id = s.event_id
         WHERE NOT e.cancelled AND s.starts_at <= now()
           AND s.starts_at >= (?::date)::timestamp AT TIME ZONE 'Europe/Zurich'
           AND s.starts_at < ((?::date) + 1)::timestamp AT TIME ZONE 'Europe/Zurich'
           AND (v.member_id = $person OR v.member_id IN (
                SELECT gs.minor_member_id FROM member_guardianships gs JOIN members minor ON minor.id = gs.minor_member_id
                WHERE gs.guardian_member_id = $person AND minor.archived_at IS NULL AND ${AgeSql.minor("minor")})))"""

    /** Liable people (alias `p`); needs `clubs c`. */
    private val liable = """
        SELECT p.id, p.first_name, p.last_name FROM members p JOIN clubs c ON TRUE
        WHERE p.archived_at IS NULL AND ${AgeSql.adult("p")}
          AND (EXISTS (SELECT 1 FROM member_team_assignments a WHERE a.member_id = p.id)
               OR EXISTS (SELECT 1 FROM member_guardianships gs JOIN members minor ON minor.id = gs.minor_member_id
                          JOIN member_team_assignments a ON a.member_id = minor.id
                          WHERE gs.guardian_member_id = p.id AND minor.archived_at IS NULL AND ${AgeSql.minor("minor")}))"""

    /** The overview for `MANAGE_FEES` holders, checked in the same statement; null when the actor may not see it. */
    fun overview(actor: UUID, season: Season): List<Row>? {
        if (database.fetchOne("SELECT ${ActorSql.holdsClubCapability(Capability.MANAGE_FEES)}", actor)?.get(0, Boolean::class.java) != true) return null
        return database.fetch(
            """WITH people AS ($liable)
               SELECT people.id, people.first_name || ' ' || people.last_name AS display_name, ${doneSql("people.id")} AS done
               FROM people JOIN clubs c ON TRUE
               WHERE ${ActorSql.holdsClubCapability(Capability.MANAGE_FEES)}
               ORDER BY done, people.last_name, people.first_name, people.id""",
            season.from.toString(), season.to.toString(), actor).map {
            Row(it.get("id", UUID::class.java), it.get("display_name", String::class.java), (it.get("done") as Number).toInt())
        }
    }

    /** The actor's own count, or null when they are not an active member. [liable] is false when no quota applies. */
    fun mine(actor: UUID, season: Season): Pair<Boolean, Int>? = database.fetchOne(
        """SELECT me.id IN (SELECT id FROM ($liable) people) AS liable, ${doneSql("me.id")} AS done
           FROM members me JOIN clubs c ON TRUE WHERE me.account_id = ? AND ${ActorSql.activeVerified("me")}""",
        season.from.toString(), season.to.toString(), actor)?.let { it.get("liable", Boolean::class.java) to (it.get("done") as Number).toInt() }

    /** Replaces the quota and season month; false when the actor lacks `MANAGE_FEES`. */
    fun replace(actor: UUID, settings: Settings): Boolean = database.execute(
        "UPDATE clubs SET helper_quota = ?, season_start_month = ? WHERE ${ActorSql.holdsClubCapability(Capability.MANAGE_FEES)}",
        settings.quota, settings.seasonStartMonth, actor) == 1

    companion object {
        val SWISS: ZoneId = ZoneId.of("Europe/Zurich")
    }
}
